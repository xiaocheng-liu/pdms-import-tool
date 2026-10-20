package com.moral.task;

import com.moral.csv.ByteRange;
import com.moral.csv.CarriageReturnSanitizer;
import com.moral.csv.CsvMeta;
import com.moral.csv.CsvMetaReader;
import com.moral.csv.CsvRowReader;
import com.moral.db.ConnectionFactory;
import com.moral.db.Dialect;
import com.moral.model.ConnectionConfig;
import com.moral.model.CsvTable;
import com.moral.model.ImportOptions;
import com.moral.model.TableProgress;
import com.moral.model.TableStatus;
import com.moral.util.FileSizeUtil;
import com.moral.util.PlatformUtil;
import com.moral.util.SqlErrors;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 单个分片的导入任务：独享连接、独享事务，按 [start, end) 字节区间流式读取并批量写入。
 * 整表不拆分时视为单个分片（区间为整个文件）。
 *
 * <p>极速模式（可选）：
 * <ul>
 *   <li>PostgreSQL / 人大金仓：走 COPY FROM STDIN，由服务端直接解析 CSV，速度可提升数倍；</li>
 *   <li>MySQL：走 LOAD DATA LOCAL INFILE，同样由服务端解析；</li>
 *   <li>Oracle/达梦：走 {@code INSERT /*+ APPEND *}{@code /} 直接路径插入（单分片）；</li>
 *   <li>仅确认未提交且回滚成功时允许降级；提交结果不明确时停止并提示核对数据。</li>
 * </ul>
 */
public class ShardImportTask implements Callable<Boolean> {

    /** 每处理多少行汇报一次进度 */
    private static final int REPORT_INTERVAL = 2000;
    /** 看门狗检查间隔：超过该时间没有进展就输出一条诊断日志 */
    private static final long WATCHDOG_INTERVAL_MS = 10000;

    private final CsvTable table;
    private final ByteRange range;
    private final CsvMeta meta;
    private final ConnectionConfig config;
    private final Dialect dialect;
    private final ImportOptions options;
    private final TableProgress progress;
    private final FailureRecorder recorder;
    private final AtomicBoolean cancelled;
    private final Consumer<String> logger;
    private final String tableLabel;
    private final String tableName;
    private final String insertSql;
    private final String fastInsertSql;

    /** 当前正在写入的 writer，用于"停止"时中断挂起的数据库操作 */
    private volatile BatchingWriter activeWriter;
    /** 批量加载通道（LOAD DATA）执行中的 Statement，用于超时时主动取消 */
    private volatile Statement activeStreamStatement;
    /** 连接级终止可中断 COPY 和提交阶段的阻塞。 */
    private volatile Connection activeConnection;
    /** 已同步到进度对象的成功/失败行数基线（保证运行中也能看到实时行数） */
    private long syncedSuccessRows;
    private long syncedFailedRows;
    /** 本次尝试（极速模式或标准模式）已读取的字节数，按绝对值上报给进度对象 */
    private long attemptBytes;
    /** 任务是否已结束（看门狗线程据此退出） */
    private volatile boolean finished;
    /** 最近一次有进展的时间，看门狗据此判断是否停滞 */
    private volatile long lastActivityAt = System.currentTimeMillis();
    /** 写入超时（毫秒）：0 表示关闭看门狗自动中断 */
    private final long writeTimeoutMillis;
    /** 看门狗是否已自动取消过当前批次，避免重复取消 */
    private final AtomicBoolean autoCancelled = new AtomicBoolean(false);

    public ShardImportTask(CsvTable table,
                           ByteRange range,
                           CsvMeta meta,
                           ConnectionConfig config,
                           Dialect dialect,
                           ImportOptions options,
                           TableProgress progress,
                           FailureRecorder recorder,
                           AtomicBoolean cancelled,
                           Consumer<String> logger) {
        this.table = table;
        this.range = range;
        this.meta = meta;
        this.config = config;
        this.dialect = dialect;
        this.options = options;
        this.progress = progress;
        this.recorder = recorder;
        this.cancelled = cancelled;
        this.logger = logger;
        this.writeTimeoutMillis = options.getWriteTimeoutSeconds() > 0
                ? options.getWriteTimeoutSeconds() * 1000L
                : 0L;
        this.tableName = dialect.normalizeTableName(table.getTableName());
        this.tableLabel = range.getTotal() > 1
                ? table.getTableName() + "[分片" + (range.getIndex() + 1) + "/" + range.getTotal() + "]"
                : table.getTableName();
        this.insertSql = dialect.buildInsertSql(config.getSchema(), tableName, meta.getColumns(), meta.getProfiles());
        this.fastInsertSql = dialect.buildFastInsertSql(config.getSchema(), tableName, meta.getColumns(), meta.getProfiles());
    }

    @Override
    public Boolean call() {
        Connection conn = null;
        Thread watchdog = startWatchdog();
        try {
            if (cancelled.get()) {
                return false;
            }
            conn = ConnectionFactory.create(config, options.getWriteTimeoutSeconds());
            activeConnection = conn;
            lastActivityAt = System.currentTimeMillis();
            if (cancelled.get()) {
                return false;
            }
            if (options.isFastMode() && dialect.supportsFastLoad()) {
                resetAttempt();
                Boolean fastResult = fastLoad(conn);
                if (fastResult != null) {
                    return fastResult;
                }
                // 快速路径未写入任何数据，降级为标准批量写入
            }
            resetAttempt();
            return standardLoad(conn, insertSql);
        } catch (SQLException error) {
            rollback(conn);
            String message = SqlErrors.isCancelOrTimeout(error)
                    ? "写入超时/被中断：" + brief(error.getMessage())
                    : "数据库错误：" + SqlErrors.readableMessage(error) + PlatformUtil.describeFileLockHint(error);
            recorder.record(tableLabel, 0, "SQL/" + error.getErrorCode(), message, "");
            progress.setMessage(message);
            log("[失败] " + tableLabel + "：" + message);
            return false;
        } catch (IOException error) {
            rollback(conn);
            String message = "文件读取失败：" + error.getMessage() + PlatformUtil.describeFileLockHint(error);
            recorder.record(tableLabel, 0, "IO", message, "");
            progress.setMessage(message);
            log("[失败] " + tableLabel + "：" + message);
            return false;
        } catch (RuntimeException error) {
            rollback(conn);
            String message = "导入异常：" + error.getMessage();
            recorder.record(tableLabel, 0, "RUNTIME", message, "");
            progress.setMessage(message);
            log("[失败] " + tableLabel + "：" + message);
            return false;
        } finally {
            finished = true;
            syncCounters(activeWriter);
            activeWriter = null;
            activeStreamStatement = null;
            activeConnection = null;
            if (watchdog != null) {
                watchdog.interrupt();
            }
            ConnectionFactory.closeQuietly(conn);
        }
    }

    /**
     * 看门狗：每 10 秒检查一次是否有进展。
     * 数据库调用阻塞时普通心跳（在读取循环里）也不会执行，只有独立线程才能如实汇报"卡在哪一步"。
     */
    private Thread startWatchdog() {
        Thread thread = new Thread(() -> {
            while (!finished) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException ignore) {
                    return;
                }
                if (finished) {
                    return;
                }
                try {
                    reportStall();
                } catch (RuntimeException ignore) {
                    // 诊断日志异常不影响导入
                }
            }
        }, "watchdog-" + table.getTableName());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void reportStall() {
        long idleMs = System.currentTimeMillis() - lastActivityAt;
        if (idleMs < WATCHDOG_INTERVAL_MS) {
            return;
        }
        BatchingWriter writer = activeWriter;
        StringBuilder message = new StringBuilder();
        message.append("[等待] ").append(tableLabel).append("：");
        if (writer == null) {
            message.append("正在通过批量加载通道(COPY / LOAD DATA)写入数据库");
        } else if (writer.isFlushing()) {
            message.append("正在等待数据库写入/提交");
        } else {
            message.append("正在读取 CSV 数据");
        }
        message.append("，已 ").append(idleMs / 1000).append(" 秒无进展");
        if (writer != null) {
            message.append("；已成功 ").append(writer.getSuccessRows()).append(" 行");
            if (writer.getBatchCount() > 0) {
                message.append("，批次 ").append(writer.getBatchCount())
                        .append(" 次，最近一批 ").append(writer.getLastBatchMillis()).append(" ms");
            }
        }
        message.append(" —— 若长时间不变，请检查目标库锁等待与资源，或点「停止」中断");
        log(message.toString());
        maybeCancelStalledBatch(writer);
    }

    /**
     * 当前批次执行/提交超过写入超时且仍无响应时，主动取消该批次。
     * 终止连接后不自动重放，避免提交阶段中断导致结果不明确时重复写入。
     */
    private void maybeCancelStalledBatch(BatchingWriter writer) {
        if (writeTimeoutMillis <= 0) {
            return;
        }
        if (writer == null) {
            // 批量加载通道（COPY / LOAD DATA）：没有 BatchingWriter，按最近一次进展时间判断
            if (activeConnection == null || System.currentTimeMillis() - lastActivityAt < writeTimeoutMillis) {
                return;
            }
            if (!autoCancelled.compareAndSet(false, true)) {
                return;
            }
            log("[中断] " + tableLabel + "：批量加载已 " + (writeTimeoutMillis / 1000)
                    + " 秒无响应，正在终止连接；请核对目标数据后重试");
            ConnectionFactory.abortQuietly(activeConnection);
            return;
        }
        if (!writer.isFlushing()) {
            return;
        }
        long startedAt = writer.getBatchStartedAt();
        if (startedAt <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed < writeTimeoutMillis) {
            return;
        }
        if (!autoCancelled.compareAndSet(false, true)) {
            return;
        }
        log("[中断] " + tableLabel + "：当前批次已 " + (elapsed / 1000) + " 秒无响应"
                + "（写入超时 " + (writeTimeoutMillis / 1000) + " 秒），正在终止连接，请核对目标数据后重试");
        ConnectionFactory.abortQuietly(activeConnection);
        writer.cancelCurrent();
    }

    // ==================== 极速模式 ====================

    /**
     * 开始一次新的读取尝试（极速模式失败后会降级重读同一个分片）。
     * 字节进度按"本次尝试的绝对值"上报，重读会覆盖而不是叠加，避免进度超过 100%。
     */
    private void resetAttempt() {
        attemptBytes = 0;
        autoCancelled.set(false);
        lastActivityAt = System.currentTimeMillis();
        progress.reportBytes(range.getIndex(), 0);
    }

    /** 累加本次尝试已读字节并同步到进度对象（绝对值上报） */
    private void reportBytes(long delta) {
        if (delta <= 0) {
            return;
        }
        attemptBytes += delta;
        progress.reportBytes(range.getIndex(), Math.min(attemptBytes, range.size()));
    }

    /**
     * 快速路径。
     *
     * @return true/false 表示已完成；null 表示快速路径未生效（未写入任何数据），需降级标准模式
     */
    private Boolean fastLoad(Connection conn) throws SQLException, IOException {
        if (!StandardCharsets.UTF_8.equals(options.getEncoding())) {
            log("[提示] " + tableLabel + "：极速模式仅支持 UTF-8，当前编码将自动降级为标准写入");
            return null;
        }
        String copySql = dialect.buildCopyInSql(config.getSchema(), tableName, meta.getColumns(),
                options.isEmptyAsNull(), range.getIndex() == 0);
        if (copySql != null) {
            return copyLoad(conn, copySql);
        }
        String loadSql = dialect.buildLoadDataSql(config.getSchema(), tableName, meta.getColumns(),
                options.isEmptyAsNull(), range.getIndex() == 0);
        if (loadSql != null) {
            return loadDataLoad(conn, loadSql);
        }
        return fastPathInsert(conn);
    }

    /** PostgreSQL：COPY FROM STDIN */
    private Boolean copyLoad(Connection conn, String copySql) throws SQLException, IOException {
        long written;
        CarriageReturnSanitizer sanitizer = null;
        InputStream raw = CsvRowReader.openRaw(table.getFile(), range.getStart(), range.getEnd());
        InputStream data = raw;
        if (options.isSanitizeCarriageReturn()) {
            sanitizer = new CarriageReturnSanitizer(raw);
            data = sanitizer;
        }
        try (InputStream stream = data) {
            written = dialect.copyIn(conn, copySql, stream,
                    bytes -> {
                        reportBytes(bytes);
                        lastActivityAt = System.currentTimeMillis();
                    },
                    cancelled::get);
        } catch (SQLException error) {
            // 回滚未确认或超时已终止连接时，不允许从头重试。
            rollbackOrRethrow(conn, error);
            if (cancelled.get()) {
                return false;
            }
            if (autoCancelled.get()) {
                throw error;
            }
            log("[降级] " + tableLabel + "：极速模式(COPY)失败，改用标准模式重试：" + brief(error.getMessage())
                    + carriageReturnHint(error));
            return null;
        }
        if (cancelled.get()) {
            rollback(conn);
            return false;
        }
        if (autoCancelled.get()) {
            throw new SQLException("批量加载超时，连接已终止，请核对目标数据", "57014");
        }
        conn.commit();
        progress.addRows(written);
        progress.addSuccess(written);
        logSanitized(sanitizer);
        return true;
    }

    /** COPY 报"未加引号的回车符"且尚未开启清洗时，给出可操作的修复建议 */
    private String carriageReturnHint(SQLException error) {
        if (options.isSanitizeCarriageReturn()) {
            return "";
        }
        String message = error.getMessage();
        if (message == null || !message.toLowerCase(Locale.ROOT).contains("carriage return")) {
            return "";
        }
        return "（CSV 中存在未加引号的回车符：可勾选「清洗未加引号的回车符」后重试，或修正源文件；"
                + "不处理的话标准模式会把该行拆成两行，后半段字段会错位）";
    }

    /** 清洗生效时提示替换数量，便于确认数据被改动的范围 */
    private void logSanitized(CarriageReturnSanitizer sanitizer) {
        if (sanitizer != null && sanitizer.getReplacedCount() > 0) {
            log("[提示] " + tableLabel + "：已把 " + sanitizer.getReplacedCount()
                    + " 个未加引号的回车符替换为空格（原始字段内容有变更）");
        }
    }

    /** MySQL：LOAD DATA LOCAL INFILE */
    private Boolean loadDataLoad(Connection conn, String loadSql) throws SQLException, IOException {
        long written;
        try (InputStream data = CsvRowReader.openRaw(table.getFile(), range.getStart(), range.getEnd())) {
            written = dialect.loadLocalInfile(conn, loadSql, data,
                    bytes -> {
                        reportBytes(bytes);
                        lastActivityAt = System.currentTimeMillis();
                    },
                    cancelled::get,
                    statement -> activeStreamStatement = statement);
        } catch (SQLException error) {
            rollbackOrRethrow(conn, error);
            if (cancelled.get()) {
                return false;
            }
            if (autoCancelled.get()) {
                throw error;
            }
            log("[降级] " + tableLabel + "：极速模式(LOAD DATA)失败，改用标准模式重试：" + brief(error.getMessage()));
            return null;
        } catch (IOException error) {
            // 取消时包装流会主动抛出，走回滚并返回"未完成"
            rollback(conn);
            if (cancelled.get()) {
                return false;
            }
            throw error;
        } finally {
            activeStreamStatement = null;
        }
        if (cancelled.get()) {
            rollback(conn);
            return false;
        }
        if (autoCancelled.get()) {
            throw new SQLException("批量加载超时，连接已终止，请核对目标数据", "57014");
        }
        conn.commit();
        progress.addRows(written);
        progress.addSuccess(written);
        return true;
    }

    /** Oracle/达梦：APPEND 直接路径插入（会话语句失败不影响主流程） */
    private Boolean fastPathInsert(Connection conn) throws SQLException, IOException {
        applySessionStatements(conn);
        BatchingWriter writer = null;
        try (PreparedStatement statement = conn.prepareStatement(fastInsertSql)) {
            writer = newWriter(conn, statement);
            runRows(writer);
            return !cancelled.get();
        } catch (SQLException error) {
            rollbackOrRethrow(conn, error);
            if ((writer == null || writer.canRetryFromStart()) && !cancelled.get() && !autoCancelled.get()) {
                log("[降级] " + tableLabel + "：极速模式(" + dialect.type().getDisplayName()
                        + " 直接路径插入)不可用，改用标准模式重试：" + brief(error.getMessage()));
                return null;
            }
            String message = SqlErrors.isCancelOrTimeout(error)
                    ? "写入超时/被中断：" + brief(error.getMessage()) + "（已超过 "
                            + options.getWriteTimeoutSeconds() + " 秒未返回）"
                    : "极速模式写入失败：" + SqlErrors.readableMessage(error);
            recorder.record(tableLabel, 0, "SQL/" + error.getErrorCode(), message, "");
            progress.setMessage(message);
            log("[失败] " + tableLabel + "：" + message);
            return false;
        } finally {
            syncCounters(writer);
        }
    }

    private void applySessionStatements(Connection conn) {
        for (String sql : dialect.fastLoadSessionStatements()) {
            try (Statement statement = conn.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                log("[提示] " + tableLabel + "：会话提速语句执行失败（已忽略）：" + brief(error.getMessage()));
            }
        }
    }

    // ==================== 标准批量写入 ====================

    private boolean standardLoad(Connection conn, String sql) throws SQLException, IOException {
        BatchingWriter writer = null;
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            writer = newWriter(conn, statement);
            runRows(writer);
            return !cancelled.get();
        } catch (SQLException error) {
            rollback(conn);
            String message = SqlErrors.isCancelOrTimeout(error)
                    ? "写入超时/被中断：" + brief(error.getMessage()) + "（已超过 "
                            + options.getWriteTimeoutSeconds() + " 秒未返回）"
                    : "数据库错误：" + SqlErrors.readableMessage(error);
            recorder.record(tableLabel, 0, "SQL/" + error.getErrorCode(), message, "");
            progress.setMessage(message);
            log("[失败] " + tableLabel + "：" + message);
            return false;
        } finally {
            syncCounters(writer);
        }
    }

    /** 中断当前挂起的数据库执行（停止导入时由引擎调用） */
    public void cancelDatabaseWork() {
        ConnectionFactory.abortQuietly(activeConnection);
        BatchingWriter writer = activeWriter;
        if (writer != null) {
            writer.cancelCurrent();
        }
        Statement streamStatement = activeStreamStatement;
        if (streamStatement != null) {
            ConnectionFactory.cancelQuietly(streamStatement);
        }
    }

    private BatchingWriter newWriter(Connection conn, PreparedStatement statement) {
        BatchingWriter writer = new BatchingWriter(conn, statement, meta.getProfiles(), options.getBatchSize(),
                options.isEmptyAsNull(), options.isContinueOnError(), recorder, tableLabel,
                options.getWriteTimeoutSeconds());
        this.activeWriter = writer;
        this.syncedSuccessRows = 0;
        this.syncedFailedRows = 0;
        return writer;
    }

    /** 把 writer 的最新计数增量同步到进度对象，实现"运行中也能看到行数" */
    private void syncCounters(BatchingWriter writer) {
        if (writer == null) {
            return;
        }
        long success = writer.getSuccessRows();
        long failed = writer.getFailedRows();
        if (success > syncedSuccessRows) {
            progress.addSuccess(success - syncedSuccessRows);
            syncedSuccessRows = success;
        }
        if (failed > syncedFailedRows) {
            progress.addFailed(failed - syncedFailedRows);
            syncedFailedRows = failed;
        }
    }

    /** 结构性坏行的说明：给出字段数差异与可操作的修复建议 */
    private String describeMalformedRow(CSVRecord record, int columnCount) {
        String tip = options.isSanitizeCarriageReturn()
                ? "已开启「清洗回车符」仍有该问题，说明源文件的引号配对也可能不完整，请检查导出环节"
                : "疑似未加引号的回车符（0x0D 后不跟 0x0A）把一行拆成了两行，请勾选「清洗回车符」后重新导入";
        return "字段数 " + record.size() + " 与表头 " + columnCount + " 不一致，该行跳过；" + tip;
    }

    /** 逐行读取当前分片并写入，按批汇报进度（行数增量实时同步，运行中也能看到进度） */
    private void runRows(BatchingWriter writer) throws SQLException, IOException {
        long reportedBytes = 0;
        long reportedRows = 0;
        long rows = 0;
        long malformedRows = 0;
        int columnCount = meta.getColumns().size();
        long startedAt = System.currentTimeMillis();
        long sanitized = 0;
        lastActivityAt = startedAt;
        try (CsvRowReader reader = CsvRowReader.open(table.getFile(), options.getEncoding(),
                range.getStart(), range.getEnd(), range.getIndex() == 0,
                options.isSanitizeCarriageReturn())) {
            CSVRecord record;
            while ((record = reader.next()) != null) {
                if (cancelled.get()) {
                    writer.cancelCurrent();
                    throw new SQLException("导入已取消", "57014");
                }
                if (record.size() != columnCount) {
                    // 结构性坏行：字段数与表头对不上。绝大多数情况是源文件里有未加引号的裸回车符（0x0D 后不跟 0x0A），
                    // 把一行拆成了两行，后半行少了前面若干列（15 + 13 = 27 这类组合）。
                    // 这里必须拦住：若照常按位置绑定，后半行的值会整体左移写进前面的列。
                    writer.skipMalformedRow(record.getRecordNumber(), describeMalformedRow(record, columnCount),
                            CsvMetaReader.valuesOf(record, record.size()));
                    malformedRows++;
                    rows++;
                    continue;
                }
                if ((rows + 1) % REPORT_INTERVAL == 0) {
                    long read = reader.bytesRead();
                    reportBytes(read - reportedBytes);
                    reportedBytes = read;
                }
                writer.write(CsvMetaReader.valuesOf(record, columnCount), record.getRecordNumber());
                rows++;
                if (rows % REPORT_INTERVAL == 0) {
                    progress.addRows(rows - reportedRows);
                    reportedRows = rows;
                    syncCounters(writer);
                    lastActivityAt = System.currentTimeMillis();
                }
            }
            reportBytes(reader.bytesRead() - reportedBytes);
            progress.addRows(rows - reportedRows);
            sanitized = reader.sanitizedCount();
        }
        if (cancelled.get()) {
            writer.cancelCurrent();
            throw new SQLException("导入已取消", "57014");
        }
        writer.flush();
        syncCounters(writer);
        lastActivityAt = System.currentTimeMillis();
        if (sanitized > 0) {
            log("[提示] " + tableLabel + "：已把 " + sanitized
                    + " 个未加引号的回车符替换为空格（原始字段内容有变更）");
        }
        if (malformedRows > 0) {
            log("[警告] " + tableLabel + "：" + malformedRows
                    + " 行的字段数与表头不一致（" + columnCount + "），已逐行记录明细并跳过；"
                    + "几乎都是源文件里存在未加引号的回车符（0x0D 后不跟 0x0A）把一行拆成了两行，"
                    + "建议勾选「清洗回车符」后重新导入该文件");
        }
        log("[统计] " + tableLabel + "：成功 " + writer.getSuccessRows() + " 行，失败 "
                + writer.getFailedRows() + " 行；批次 " + writer.getBatchCount() + " 次，执行累计 "
                + writer.getExecuteMillis() + " ms，提交累计 " + writer.getCommitMillis()
                + " ms，最近一批 " + writer.getLastBatchMillis() + " ms，读取+写入总耗时 "
                + FileSizeUtil.formatDuration(System.currentTimeMillis() - startedAt));
    }

    /** 回滚失败时保留最初异常，禁止降级重放，避免连接关闭错误覆盖真实原因。 */
    private static void rollbackOrRethrow(Connection conn, SQLException original) throws SQLException {
        try {
            conn.rollback();
        } catch (SQLException rollbackError) {
            original.addSuppressed(rollbackError);
            throw original;
        }
    }

    private void rollback(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignore) {
                // 回滚失败忽略
            }
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }

    private static String brief(String message) {
        if (message == null) {
            return "";
        }
        String value = message.replace("\r", " ").replace("\n", " ");
        return value.length() > 200 ? value.substring(0, 200) + "..." : value;
    }

    /** 供界面显示的当前状态 */
    public TableStatus status() {
        return progress.getStatus();
    }

    public CsvTable getTable() {
        return table;
    }
}
