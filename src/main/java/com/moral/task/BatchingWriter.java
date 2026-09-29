package com.moral.task;

import com.moral.model.ColumnProfile;
import com.moral.model.InferredType;
import com.moral.util.SqlErrors;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;

/**
 * 批处理写入器：addBatch 累积到指定行数后 executeBatch + commit。
 *
 * <p>批量失败时的处理策略：
 * <ol>
 *   <li>先回滚当前批，再判断错误类型；</li>
 *   <li>若是数据行问题（约束冲突、数值/长度超限等），改为<b>逐行重放</b>该批：
 *       每行前打 Savepoint，坏行回滚到 Savepoint 并逐条记录明细，好行照常入库——
 *       因此每个坏行都能精确记录表名、行号、错误码、错误原因与原始数据；</li>
 *   <li>若是环境性错误（连接中断、表不存在、无权限等），整批失败只记录一条，
 *       避免把同一个连接错误重复记录成上千条明细。</li>
 * </ol>
 */
public final class BatchingWriter {

    /** 逐行重放时每多少行提交一次（配合 Savepoint 控制事务大小） */
    private static final int REPLAY_COMMIT_INTERVAL = 100;
    /** 明细中原始行的最大长度 */
    private static final int MAX_RAW_LINE = 2000;

    /** 宽容的日期时间解析器：日期必填，时间与小数秒可选（2026-09-28 / 2026-9-28 9:5:3.12） */
    private static final DateTimeFormatter FLEX_DATETIME = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
            .optionalStart()
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NOT_NEGATIVE)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NOT_NEGATIVE)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, SignStyle.NOT_NEGATIVE)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .optionalEnd()
            .optionalEnd()
            .optionalEnd()
            .toFormatter();

    private final Connection conn;
    private final PreparedStatement statement;
    private final List<ColumnProfile> profiles;
    private final int batchSize;
    private final boolean emptyAsNull;
    private final boolean continueOnError;
    private final FailureRecorder recorder;
    private final String tableLabel;
    /** 写入超时（秒）：&gt;0 时对每批 executeBatch 设置查询超时，作为看门狗之外的第二道保险 */
    private final int writeTimeoutSeconds;

    private final List<List<String>> pendingRows = new ArrayList<>();
    private final List<Long> pendingLines = new ArrayList<>();

    private volatile long successRows;
    private volatile long failedRows;

    /** 耗时统计：用于定位瓶颈在数据库执行、提交还是客户端读取 */
    private volatile long batchCount;
    private volatile long executeNanos;
    private volatile long commitNanos;
    private volatile long lastBatchNanos;
    /** 当前批次开始时间（0 表示当前没有正在执行的批次） */
    private volatile long batchStartedAt;

    public BatchingWriter(Connection conn,
                          PreparedStatement statement,
                          List<ColumnProfile> profiles,
                          int batchSize,
                          boolean emptyAsNull,
                          boolean continueOnError,
                          FailureRecorder recorder,
                          String tableLabel,
                          int writeTimeoutSeconds) {
        this.conn = conn;
        this.statement = statement;
        this.profiles = profiles;
        this.batchSize = Math.max(1, batchSize);
        this.emptyAsNull = emptyAsNull;
        this.continueOnError = continueOnError;
        this.recorder = recorder;
        this.tableLabel = tableLabel;
        this.writeTimeoutSeconds = writeTimeoutSeconds;
    }

    public long getSuccessRows() {
        return successRows;
    }

    public long getFailedRows() {
        return failedRows;
    }

    public int pending() {
        return pendingRows.size();
    }

    /** 已执行的批次数 */
    public long getBatchCount() {
        return batchCount;
    }

    /**
     * 中断当前正在执行/提交的数据库操作（供"停止导入"使用）。
     * JDBC 的 executeBatch/commit 不响应线程中断，必须显式 cancel 才能立刻停下来。
     */
    public void cancelCurrent() {
        try {
            statement.cancel();
        } catch (SQLException ignore) {
            // 语句已执行完或驱动不支持取消，忽略
        }
    }

    /** 累计 executeBatch 耗时（毫秒） */
    public long getExecuteMillis() {
        return executeNanos / 1_000_000L;
    }

    /** 累计 commit 耗时（毫秒） */
    public long getCommitMillis() {
        return commitNanos / 1_000_000L;
    }

    /** 最近一批的总耗时（毫秒），持续偏大说明数据库写入出现瓶颈 */
    public long getLastBatchMillis() {
        return lastBatchNanos / 1_000_000L;
    }

    /** 加入一行，达到批量大小时自动提交（values 由调用方逐行新建，此处直接持有引用以避免复制开销） */
    public void write(List<String> values, long lineNo) throws SQLException {
        try {
            bind(values);
        } catch (SQLException error) {
            // 参数绑定阶段发现的数据问题（数值/日期格式非法、超出字段长度等）：
            // 直接记录该行并跳过，不影响同一批里的其它行
            failedRows++;
            recorder.record(tableLabel, lineNo, describeCode(error), oneLine(error.getMessage()), toCsvLine(values));
            if (!continueOnError) {
                throw error;
            }
            return;
        }
        statement.addBatch();
        pendingRows.add(values);
        pendingLines.add(lineNo);
        if (pendingRows.size() >= batchSize) {
            flush();
        }
    }

    /** 提交剩余批次，并统计各阶段耗时（执行 / 提交） */
    public void flush() throws SQLException {
        if (pendingRows.isEmpty()) {
            return;
        }
        int size = pendingRows.size();
        long start = System.nanoTime();
        batchStartedAt = System.currentTimeMillis();
        try {
            if (writeTimeoutSeconds > 0) {
                statement.setQueryTimeout(writeTimeoutSeconds);
            }
            statement.executeBatch();
            long afterExecute = System.nanoTime();
            conn.commit();
            long afterCommit = System.nanoTime();
            batchCount++;
            executeNanos += (afterExecute - start);
            commitNanos += (afterCommit - afterExecute);
            successRows += size;
        } catch (SQLException error) {
            rollbackQuietly();
            handleBatchError(error, size);
        } finally {
            lastBatchNanos = System.nanoTime() - start;
            batchStartedAt = 0;
            pendingRows.clear();
            pendingLines.clear();
            clearBatchQuietly();
        }
    }

    /** 当前是否正在执行批次（用于诊断：卡在数据库写入还是客户端读取） */
    public boolean isFlushing() {
        return batchStartedAt > 0;
    }

    /** 当前批次的开始时间（毫秒），0 表示当前没有正在执行的批次 */
    public long getBatchStartedAt() {
        return batchStartedAt;
    }

    /**
     * 批量失败处理：数据行错误走逐行重放精确定位，环境性错误只记录一条。
     */
    private void handleBatchError(SQLException error, int size) throws SQLException {
        // 取消/超时不是数据行问题：必须强制上抛，否则会被"失败跳过继续"静默吞掉，
        // 导致整批丢失但整表仍被标记成功
        if (SqlErrors.isCancelOrTimeout(error)) {
            failedRows += size;
            recorder.record(tableLabel, firstLineNo(), describeCode(error),
                    oneLine(error.getMessage()), "");
            throw error;
        }
        if (!isDataRowError(error)) {
            failedRows += size;
            recorder.record(tableLabel, firstLineNo(), describeCode(error),
                    oneLine(error.getMessage()), "");
            if (!continueOnError) {
                throw error;
            }
            return;
        }
        if (!continueOnError) {
            failedRows += size;
            recorder.record(tableLabel, firstLineNo(), describeCode(error),
                    oneLine(error.getMessage()), "");
            throw error;
        }
        replayRowByRow();
    }

    /** 逐行重放本批：坏行精确定位并记录，好行入库 */
    private void replayRowByRow() throws SQLException {
        int replayed = 0;
        for (int i = 0; i < pendingRows.size(); i++) {
            List<String> row = pendingRows.get(i);
            long lineNo = i < pendingLines.size() ? pendingLines.get(i) : 0L;
            Savepoint savepoint = createSavepoint();
            if (savepoint == null) {
                // 驱动不支持 Savepoint：先提交已成功的数据，避免被坏行连累
                commitQuietly();
            }
            try {
                bind(row);
                statement.executeUpdate();
                successRows++;
            } catch (SQLException rowError) {
                failedRows++;
                if (savepoint != null) {
                    rollbackToQuietly(savepoint);
                } else {
                    rollbackQuietly();
                }
                recorder.record(tableLabel, lineNo, describeCode(rowError),
                        oneLine(rowError.getMessage()), toCsvLine(row));
                if (SqlErrors.isCancelOrTimeout(rowError)) {
                    // 重放过程中被取消/超时：剩余行不再尝试，直接上抛交给外层降级或失败
                    failedRows += (pendingRows.size() - i - 1);
                    throw rowError;
                }
                if (!continueOnError || !isDataRowError(rowError)) {
                    // 环境性错误：剩余行不再重放，避免刷屏与无效尝试
                    failedRows += (pendingRows.size() - i - 1);
                    break;
                }
            }
            replayed++;
            if (replayed % REPLAY_COMMIT_INTERVAL == 0) {
                commitQuietly();
            }
        }
        commitQuietly();
    }

    /** 约束冲突、数值/长度超限等属于"数据行自身"的错误，可逐行定位 */
    private static boolean isDataRowError(SQLException error) {
        String state = error.getSQLState();
        if (state == null || state.length() < 2) {
            return true;
        }
        String category = state.substring(0, 2);
        return "22".equals(category)   // 数据异常（数值、长度、日期格式）
                || "23".equals(category)   // 完整性约束冲突（唯一键、非空、外键、CHECK）
                || "21".equals(category)   // 基数违例
                || "01".equals(category);  // 警告类
    }

    private long firstLineNo() {
        return pendingLines.isEmpty() ? 0L : pendingLines.get(0);
    }

    private Savepoint createSavepoint() {
        try {
            return conn.setSavepoint();
        } catch (SQLException | AbstractMethodError ignore) {
            return null;
        }
    }

    private void rollbackToQuietly(Savepoint savepoint) {
        try {
            conn.rollback(savepoint);
        } catch (SQLException ignore) {
            rollbackQuietly();
        }
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            // 回滚失败不中断流程
        }
    }

    private void commitQuietly() {
        try {
            conn.commit();
        } catch (SQLException ignore) {
            // 提交失败由后续操作报错体现
        }
    }

    private void clearBatchQuietly() {
        try {
            statement.clearBatch();
        } catch (SQLException ignore) {
            // 清空批次失败忽略
        }
    }

    private void bind(List<String> values) throws SQLException {
        int count = profiles.size();
        for (int i = 0; i < count; i++) {
            String value = i < values.size() ? values.get(i) : "";
            if (value == null) {
                value = "";
            }
            int index = i + 1;
            ColumnProfile profile = profiles.get(i);
            if (value.isEmpty() && emptyAsNull) {
                statement.setNull(index, Types.VARCHAR);
                continue;
            }
            switch (profile.getType()) {
                case LONG: {
                    Long number = parseLong(value);
                    if (number == null) {
                        throw dataError(profile, value, "22018", 22018, "不是合法整数");
                    }
                    statement.setLong(index, number);
                    break;
                }
                case DOUBLE: {
                    Double number = parseDouble(value);
                    if (number == null) {
                        throw dataError(profile, value, "22018", 22018, "不是合法数字");
                    }
                    statement.setDouble(index, number);
                    break;
                }
                case DATE:
                case DATETIME: {
                    // 直接绑定 Timestamp 值，交给驱动按目标列类型转换，
                    // 不再拼接 TO_DATE/TO_TIMESTAMP，避免日期格式与 NLS 设置不一致导致 ORA-01858
                    Timestamp timestamp = parseTimestamp(value);
                    if (timestamp == null) {
                        throw dataError(profile, value, "22007", 22007,
                                "不是合法日期时间（支持 2026-09-28、2026-09-28 09:59:47、2026/9/28 等格式）");
                    }
                    statement.setTimestamp(index, timestamp);
                    break;
                }
                case TEXT:
                    statement.setCharacterStream(index, new StringReader(value), value.length());
                    break;
                default:
                    statement.setString(index, value);
                    break;
            }
        }
    }

    /** 构造带明确原因的数据行错误，便于精确记录到失败明细 */
    private static SQLException dataError(ColumnProfile profile, String value,
                                          String sqlState, int errorCode, String reason) {
        return new SQLException("列 " + profile.getName() + " 的值「" + abbreviate(value) + "」" + reason,
                sqlState, errorCode);
    }

    private static String abbreviate(String value) {
        String oneLine = value.replace("\r", " ").replace("\n", " ");
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "..." : oneLine;
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    /** 宽容解析常见日期时间写法：2026-09-28、2026-09-28 09:59:47、2026/9/28 9:59、带毫秒、带 T 分隔等 */
    private static Timestamp parseTimestamp(String value) {
        String text = value.trim().replace('T', ' ').replace('/', '-');
        if (text.isEmpty()) {
            return null;
        }
        try {
            TemporalAccessor parsed = FLEX_DATETIME.parse(text);
            LocalDate date = LocalDate.from(parsed);
            int hour = parsed.isSupported(ChronoField.HOUR_OF_DAY) ? parsed.get(ChronoField.HOUR_OF_DAY) : 0;
            int minute = parsed.isSupported(ChronoField.MINUTE_OF_HOUR) ? parsed.get(ChronoField.MINUTE_OF_HOUR) : 0;
            int second = parsed.isSupported(ChronoField.SECOND_OF_MINUTE) ? parsed.get(ChronoField.SECOND_OF_MINUTE) : 0;
            int nano = parsed.isSupported(ChronoField.NANO_OF_SECOND) ? parsed.get(ChronoField.NANO_OF_SECOND) : 0;
            return Timestamp.valueOf(LocalDateTime.of(date, LocalTime.of(hour, minute, second, nano)));
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    private static String describeCode(SQLException error) {
        String state = error.getSQLState();
        return (state == null ? "" : state) + "/code=" + error.getErrorCode();
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace("\r", " ").replace("\n", " ");
        return value.length() > 500 ? value.substring(0, 500) + "..." : value;
    }

    /** 还原为一行标准 CSV，便于修复后直接重放 */
    private static String toCsvLine(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            String value = values.get(i) == null ? "" : values.get(i);
            builder.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        String line = builder.toString();
        return line.length() > MAX_RAW_LINE ? line.substring(0, MAX_RAW_LINE) + "...(已截断)" : line;
    }
}
