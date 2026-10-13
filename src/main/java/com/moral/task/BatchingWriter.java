package com.moral.task;

import com.moral.model.ColumnProfile;
import com.moral.db.ConnectionFactory;
import com.moral.model.InferredType;
import com.moral.util.SqlErrors;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * 错误信息里的目标类型长度：character varying(258) / varchar2(100) / char(10)；
     * 以及金仓简体中文提示里的长度写法：对于可变字符类型来说，值太长了(96)
     */
    private static final Pattern TYPE_LENGTH = Pattern.compile(
            "(?:character varying|nvarchar2|varchar2|varchar|character|char)\\s*\\(\\s*(\\d+)\\s*\\)"
                    + "|(?:值太长|太长|过长|超长)[^0-9\\n]{0,6}[（(]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    /** 错误消息里已经出现的列名：for column 'xxx' / 列 "xxx" */
    private static final Pattern COLUMN_IN_MESSAGE = Pattern.compile(
            "(?:for\\s+column\\s+['\"`]?\\w+|列\\s*['\"`]?\\w+)", Pattern.CASE_INSENSITIVE);
    /** 服务端大小上限：btree version 4 maximum 2704 / maximum size 8160 */
    private static final Pattern MAX_SIZE = Pattern.compile(
            "maximum(?:\\s+size)?\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

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
    private long pendingPayloadBytes;
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
    private volatile boolean cancellationRequested;
    /** 提交或回滚结果不确定时，禁止从头重试。 */
    private boolean transactionUncertain;

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
        cancellationRequested = true;
        ConnectionFactory.cancelQuietly(statement);
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

    /**
     * 记录在读取阶段就被判定为结构损坏、根本没拿去绑定/入库的行。
     *
     * <p>典型成因：源文件里出现"未加引号的裸回车符"，一行被拆成两行，后半行少了前面若干列。
     * 这类行若按位置绑定，后半行的值会整体左移写进前面的列（例如把证型文本写进整型 ID 列，
     * 报 22018 不是合法整数），既看不出真实原因，也可能悄悄把数据写错位置。
     */
    public void skipMalformedRow(long lineNo, String message, List<String> values) throws SQLException {
        failedRows++;
        recorder.record(tableLabel, lineNo, "ROW_FORMAT", message, toCsvLine(values));
        if (!continueOnError) {
            throw new SQLException(message, "22000");
        }
    }

    /** 加入一行，达到批量大小时自动提交（values 由调用方逐行新建，此处直接持有引用以避免复制开销） */
    public void write(List<String> values, long lineNo) throws SQLException {
        checkCancelled();
        long rowBytes = estimatedPayloadBytes(values);
        // 按字符数据的保守字节估算限制批次，单条超大记录仍完整单独写入。
        if (!pendingRows.isEmpty() && pendingPayloadBytes + rowBytes > 8L * 1024 * 1024) {
            flush();
        }
        try {
            bind(values);
        } catch (SQLException error) {
            // 参数绑定阶段发现的数据问题（数值/日期格式非法、超出字段长度等）：
            // 直接记录该行并跳过，不影响同一批里的其它行
            failedRows++;
            recorder.record(tableLabel, lineNo, describeCode(error),
                    SqlErrors.readableMessage(error) + describeOverflow(error, Collections.singletonList(values)),
                    toCsvLine(values));
            if (!continueOnError || !isDataRowError(error) || SqlErrors.isCancelOrTimeout(error)) {
                throw error;
            }
            return;
        }
        statement.addBatch();
        pendingRows.add(values);
        pendingLines.add(lineNo);
        pendingPayloadBytes += rowBytes;
        if (pendingRows.size() >= batchSize || pendingPayloadBytes >= 8L * 1024 * 1024) {
            flush();
        }
    }

    /** 每个 UTF-16 单元按最多三个 UTF-8 字节估算，避免为大文本创建临时字节数组。 */
    private static long estimatedPayloadBytes(List<String> values) {
        long bytes = 0;
        for (String value : values) {
            if (value != null) bytes += value.length() * 3L;
        }
        return bytes;
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
            try {
                checkCancelled();
                if (writeTimeoutSeconds > 0) {
                    statement.setQueryTimeout(writeTimeoutSeconds);
                }
                statement.executeBatch();
            } catch (SQLException error) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    transactionUncertain = true;
                    error.addSuppressed(rollbackError);
                    failedRows += size;
                    recorder.record(tableLabel, firstLineNo(), describeCode(error),
                            "批次执行失败，回滚也失败；禁止重放，请核对目标数据。原始错误："
                                    + SqlErrors.readableMessage(error) + "；回滚错误："
                                    + SqlErrors.readableMessage(rollbackError), "");
                    throw error;
                }
                handleBatchError(error, size);
                return;
            }
            long afterExecute = System.nanoTime();
            try {
                commitRows(size);
            } catch (SQLException error) {
                rollbackQuietly();
                failedRows += size;
                recorder.record(tableLabel, firstLineNo(), describeCode(error),
                        "提交失败或被取消，禁止自动重放，请核对目标数据：" + SqlErrors.readableMessage(error), "");
                throw error;
            }
            long afterCommit = System.nanoTime();
            batchCount++;
            executeNanos += (afterExecute - start);
            commitNanos += (afterCommit - afterExecute);
        } finally {
            lastBatchNanos = System.nanoTime() - start;
            batchStartedAt = 0;
            pendingPayloadBytes = 0;
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
                    SqlErrors.readableMessage(error), "");
            throw error;
        }
        if (!isDataRowError(error)) {
            failedRows += size;
            recorder.record(tableLabel, firstLineNo(), describeCode(error),
                    SqlErrors.readableMessage(error) + describeTypeLookupHint(error, size), "");
            throw error;
        }
        if (!continueOnError) {
            failedRows += size;
            // 不逐行重放时整批只有一条明细，必须在此补出"疑似超长列"，否则无法定位字段
            recorder.record(tableLabel, firstLineNo(), describeCode(error),
                    SqlErrors.readableMessage(error) + describeOverflow(error, pendingRows), "");
            throw error;
        }
        replayRowByRow();
    }

    /** 逐行重放本批：坏行精确定位并记录，好行入库 */
    private void replayRowByRow() throws SQLException {
        long successBefore = successRows;
        long failedBefore = failedRows;
        int uncommitted = 0;
        try {
            statement.clearBatch();
            for (int i = 0; i < pendingRows.size(); i++) {
                checkCancelled();
                List<String> row = pendingRows.get(i);
                long lineNo = pendingLines.get(i);
                Savepoint savepoint = createSavepoint();
                if (savepoint == null) {
                    commitRows(uncommitted);
                    uncommitted = 0;
                }
                try {
                    bind(row);
                    statement.executeUpdate();
                    uncommitted++;
                } catch (SQLException rowError) {
                    failedRows++;
                    recorder.record(tableLabel, lineNo, describeCode(rowError),
                            SqlErrors.readableMessage(rowError)
                                    + describeOverflow(rowError, Collections.singletonList(row)), toCsvLine(row));
                    if (SqlErrors.isCancelOrTimeout(rowError) || !isDataRowError(rowError)) {
                        throw rowError;
                    }
                    // 保存点回滚失败时终止整批，不能把已被回滚的好行计为成功。
                    if (savepoint != null) {
                        conn.rollback(savepoint);
                    } else {
                        conn.rollback();
                    }
                }
                if (savepoint == null || (i + 1) % REPLAY_COMMIT_INTERVAL == 0) {
                    commitRows(uncommitted);
                    uncommitted = 0;
                }
            }
            commitRows(uncommitted);
        } catch (SQLException error) {
            rollbackQuietly();
            failedRows += pendingRows.size() - (successRows - successBefore) - (failedRows - failedBefore);
            throw error;
        }
    }

    /** 约束冲突、数值/长度超限、类型不匹配等属于"数据行自身"的错误，可逐行定位 */
    private static boolean isDataRowError(SQLException error) {
        String state = error.getSQLState();
        if (state == null || state.length() < 2) {
            return true;
        }
        String category = state.substring(0, 2);
        // 42804 = datatype_mismatch：多为空值/个别行与目标列类型不符，属于"该行"的问题；
        // 归入数据行错误后走逐行重放，只丢坏行，而不是像环境性错误那样整批 1000 行一起丢
        return "22".equals(category)   // 数据异常（数值、长度、日期格式）
                || "23".equals(category)   // 完整性约束冲突（唯一键、非空、外键、CHECK）
                || "21".equals(category)   // 基数违例
                || "01".equals(category)   // 警告类
                || "42804".equals(state)
                // 54 = program_limit_exceeded（PG / 金仓：btree 索引行超限、行过大）由具体行的
                // 字段长度触发，逐行重放可精确定位坏行并保住好行；内存不足等其余 54 类仍按环境性错误处理
                || SqlErrors.isRowSizeLimit(error);
    }

    private long firstLineNo() {
        return pendingLines.isEmpty() ? 0L : pendingLines.get(0);
    }

    private Savepoint createSavepoint() throws SQLException {
        try {
            return conn.setSavepoint();
        } catch (SQLFeatureNotSupportedException | AbstractMethodError ignore) {
            return null;
        }
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            transactionUncertain = true;
        }
    }

    /** 只有数据库确认提交后才计入成功行数。 */
    private void commitRows(int rows) throws SQLException {
        if (rows == 0) {
            return;
        }
        checkCancelled();
        try {
            conn.commit();
        } catch (SQLException error) {
            transactionUncertain = true;
            throw error;
        }
        successRows += rows;
    }

    /** 仅未产生结果且事务状态明确时允许快速模式从头降级。 */
    public boolean canRetryFromStart() {
        return successRows == 0 && failedRows == 0 && !transactionUncertain && !cancellationRequested;
    }

    /** 取消标记覆盖读取、重放和提交前的检查点。 */
    private void checkCancelled() throws SQLException {
        if (cancellationRequested) {
            throw new SQLException("导入已取消", "57014");
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
                // 必须按列类型发 NULL：统一用 Types.VARCHAR 时，PostgreSQL 会认为参数是 character varying，
                // 写入 timestamp / numeric 列会报 42804（类型不匹配）并拖垮整批
                statement.setNull(index, sqlTypeOf(profile.getType()));
                continue;
            }
            switch (profile.getType()) {
                case LONG: {
                    checkNumericRepresentation(profile, value);
                    Long number = parseLong(value);
                    if (number == null) {
                        throw dataError(profile, value, "22018", 22018, "不是合法整数");
                    }
                    statement.setLong(index, number);
                    break;
                }
                case DOUBLE:
                case DECIMAL: {
                    checkNumericRepresentation(profile, value);
                    BigDecimal number = parseDecimal(value);
                    if (number == null) {
                        throw dataError(profile, value, "22018", 22018, "不是合法数字");
                    }
                    statement.setBigDecimal(index, number);
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

    /** NULL 绑定用的 SQL 类型：必须与该列的推断类型一致，否则 PostgreSQL 会报 42804 */
    private static int sqlTypeOf(InferredType type) {
        switch (type) {
            case LONG:
                return Types.BIGINT;
            case DOUBLE:
                return Types.DOUBLE;
            case DECIMAL:
                return Types.DECIMAL;
            case DATE:
            case DATETIME:
                return Types.TIMESTAMP;
            default:
                return Types.VARCHAR;
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

    /** 采样外出现编码式前导零时保留失败原文，不能静默丢零。 */
    private static void checkNumericRepresentation(ColumnProfile profile, String value) throws SQLException {
        if (profile.getDeclaredSqlType() != null) {
            return; // 明确定义为数值列时，允许 CSV 使用前导零表示数字
        }
        String text = value.trim();
        int start = text.startsWith("-") || text.startsWith("+") ? 1 : 0;
        if (text.length() > start + 1 && text.charAt(start) == '0'
                && text.charAt(start + 1) >= '0' && text.charAt(start + 1) <= '9') {
            throw dataError(profile, value, "22018", 22018,
                    "含前导零，与采样数值类型冲突；请按字符列导入，避免编码丢零");
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value.trim());
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
        String state = error.getSQLState() == null ? "" : error.getSQLState();
        // PostgreSQL 驱动的 vendor code 恒为 0，打印 "code=0" 只有干扰，省略
        return error.getErrorCode() == 0 ? state : state + "/code=" + error.getErrorCode();
    }

    /**
     * 长度/大小超限类错误（典型：PG 22001 "value too long for type character varying(258)"、
     * PG / 金仓 54000 "index row size 3000 exceeds btree version 4 maximum 2704"）时，
     * 数据库返回的消息里只有<b>目标长度</b>、不含列名，无法直接看出是哪个字段超长。
     * 此处按错误信息里的长度上限，逐列比对本行（或本批各行）的值长度，补出"疑似超长列"。
     */
    private String describeOverflow(SQLException error, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        String message = SqlErrors.readableMessage(error, 2000);
        // MySQL（Data too long for column 'x'）与 Oracle（value too large for column "X"）
        // 的错误消息本身就带列名，无需再猜测
        if (COLUMN_IN_MESSAGE.matcher(message).find()) {
            return "";
        }
        int limit = extractLimit(error, message);
        if (limit <= 0) {
            return "";
        }
        String overflow = findOverflow(rows, limit, false);
        if (overflow.isEmpty()) {
            // 按字节比对：btree 索引行、varchar2(n BYTE) 等均按字节限制，中文字符数与字节数不一致
            overflow = findOverflow(rows, limit, true);
        }
        return overflow;
    }

    /** 取出错误消息里的上限值：字段长度取声明长度，索引行/行大小取服务端最大值 */
    private static int extractLimit(SQLException error, String message) {
        if (isLengthError(error)) {
            return extractLengthLimit(message);
        }
        if (SqlErrors.isRowSizeLimit(error)) {
            return extractSizeLimit(message);
        }
        return -1;
    }

    /** 服务端大小上限：index row size 3000 exceeds btree version 4 maximum 2704 / row is too big: size 10000, maximum size 8160 */
    private static int extractSizeLimit(String message) {
        Matcher matcher = MAX_SIZE.matcher(message);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignore) {
            return -1;
        }
    }

    /**
     * 整批被数据库级限制（SQLSTATE 54）拒绝时的补充提示。
     * 只记录"第 N 行"会被误读成个别坏行，必须点明整批都没入库，并给出 PG / 金仓最常见的排查方向。
     */
    private String describeProgramLimitHint(SQLException error, int size) {
        if (!SqlErrors.isProgramLimit(error)) {
            return "";
        }
        String hint = SqlErrors.isRowSizeLimit(error)
                ? "目标表存在索引行/行大小上限（PG、金仓 btree 单条目上限约 2704 字节）："
                        + "请检查 content 等长文本字段上是否建了普通 btree 索引，导入前可先删除该索引或改用全文索引"
                : "多为服务端内存/资源限制，请检查数据库内存参数与磁盘空间";
        return "；整批 " + size + " 行均未入库（数据库级限制，不是个别坏行）——" + hint;
    }

    /**
     * 环境性错误的补充提示：除 54 类资源限制外，还要覆盖
     * {@code XX000 cache lookup failed for type} —— 同样会让整批失败且与数据无关，
     * 但成因是驱动/计划缓存里的类型 OID 失效，必须给出完全不同的排查方向。
     */
    private String describeTypeLookupHint(SQLException error, int size) {
        if (!SqlErrors.isUnknownTypeLookup(error)) {
            return describeProgramLimitHint(error, size);
        }
        return "；整批 " + size + " 行均未入库（服务端无法识别参数类型 OID，与数据内容无关）——"
                + "常见于旧版 Kingbase 搭配高版本 kingbase8 驱动：工具建连时会自动切换到 simple 查询协议重试；"
                + "若仍出现，请检查目标表是否在导入过程中被删除重建";
    }

    /** 在给定行里找出超限（或最长）的列：byByte=false 按字符数，true 按 UTF-8 字节数 */
    private String findOverflow(List<List<String>> rows, int limit, boolean byByte) {
        int maxLength = -1;
        int columnIndex = -1;
        String sample = "";
        for (List<String> row : rows) {
            if (row == null) {
                continue;
            }
            for (int i = 0; i < row.size(); i++) {
                String value = row.get(i);
                int length = value == null ? 0
                        : (byByte ? value.getBytes(StandardCharsets.UTF_8).length : value.length());
                // 已知目标长度时只关注超限的列；未知长度时退化为"最长列"提示
                if (limit > 0 && length <= limit) {
                    continue;
                }
                if (length > maxLength) {
                    maxLength = length;
                    columnIndex = i;
                    sample = abbreviate(value == null ? "" : value);
                }
            }
        }
        if (columnIndex < 0) {
            return "";
        }
        String column = columnIndex < profiles.size()
                ? profiles.get(columnIndex).getName()
                : "第" + (columnIndex + 1) + "列";
        String unit = byByte ? "字节" : "字符";
        String detail = limit > 0 ? maxLength + " " + unit + " > 限制 " + limit : "最长 " + maxLength + " " + unit;
        return "；疑似超长列：" + column + "（" + detail + "，值：「" + sample + "」）";
    }

    /** 是否为"值超长/超出字段长度"类错误 */
    private static boolean isLengthError(SQLException error) {
        String state = error.getSQLState();
        // 22001 = string_data_right_truncation，22026/22027 为部分库的长度类错误码
        if ("22001".equals(state) || "22026".equals(state) || "22027".equals(state)) {
            return true;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("too long")
                || lower.contains("too large")
                || message.contains("过长")
                || message.contains("超长")
                || message.contains("超出长度");
    }

    /** 从错误信息中提取目标类型长度，如 character varying(258) / varchar2(100) / 值太长了(96) → 258 / 100 / 96 */
    private static int extractLengthLimit(String message) {
        Matcher matcher = TYPE_LENGTH.matcher(message);
        if (!matcher.find()) {
            return -1;
        }
        // 多个候选分组：中英文两种写法只有一个能命中
        for (int group = 1; group <= matcher.groupCount(); group++) {
            String value = matcher.group(group);
            if (value == null) {
                continue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
                // 该分组没命中，继续看下一个
            }
        }
        return -1;
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
        return builder.toString();
    }
}
