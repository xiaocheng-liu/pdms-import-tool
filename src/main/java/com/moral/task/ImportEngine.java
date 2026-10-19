package com.moral.task;

import com.moral.csv.ByteRange;
import com.moral.csv.CsvMeta;
import com.moral.csv.CsvMetaReader;
import com.moral.csv.DdlBuilder;
import com.moral.csv.OracleTableDefinitions;
import com.moral.csv.SplitPlanner;
import com.moral.db.ConnectionFactory;
import com.moral.db.Dialect;
import com.moral.db.DialectFactory;
import com.moral.db.ProtocolCompat;
import com.moral.db.TargetColumnTypes;
import com.moral.model.ConnectionConfig;
import com.moral.model.CsvTable;
import com.moral.model.DbType;
import com.moral.model.ImportOptions;
import com.moral.model.ImportResult;
import com.moral.model.SchemaPlan;
import com.moral.model.TableProgress;
import com.moral.model.TableStatus;
import com.moral.util.FileSizeUtil;
import com.moral.util.PlatformUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 导入总控：采样 → 自动建表 → 清表 → 分片编排 → 并发执行 → 汇总。
 * 连接数上限等于并发线程数（另有一路用于建表/清表）。
 */
public final class ImportEngine {

    /** 界面日志最多回显多少条失败明细，避免大量坏行刷爆日志区 */
    private static final int MAX_FAILURE_LOG_LINES = 300;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<ShardImportTask> submittedTasks = Collections.synchronizedList(new ArrayList<>());
    private volatile ExecutorService pool;
    private volatile Connection prepareConnection;

    /**
     * 停止导入：先中断各任务正在挂起的数据库操作（JDBC 的 executeBatch/commit 不响应线程中断，
     * 必须显式 statement.cancel()），再关闭线程池。
     */
    public void cancel() {
        cancelled.set(true);
        ConnectionFactory.abortQuietly(prepareConnection);
        List<ShardImportTask> tasks;
        synchronized (submittedTasks) {
            tasks = new ArrayList<>(submittedTasks);
        }
        for (ShardImportTask task : tasks) {
            try {
                task.cancelDatabaseWork();
            } catch (RuntimeException ignore) {
                // 单个任务取消失败不影响其它任务
            }
        }
        ExecutorService current = pool;
        if (current != null) {
            cancelQueuedTasks(current);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 预生成缺失表的建表语句（用于界面预览/编辑）。
     *
     * @return 表名 → CREATE TABLE 语句
     */
    public Map<String, String> generateMissingDdl(List<CsvTable> tables,
                                                  ConnectionConfig config,
                                                  ImportOptions options) throws Exception {
        // 无日志出口的场景（命令行调用等）静默处理
        return generateMissingDdl(tables, config, options, message -> { });
    }

    /**
     * @param logger 诊断信息出口，用于在不存在 DNS 级别异常但配置可疑时给出提示
     *               （例如同名表在其它 schema 下存在，目标 schema 可能填错）
     */
    public Map<String, String> generateMissingDdl(List<CsvTable> tables,
                                                  ConnectionConfig config,
                                                  ImportOptions options,
                                                  Consumer<String> logger) throws Exception {
        Map<String, String> ddlMap = new LinkedHashMap<>();
        Dialect dialect = DialectFactory.get(config.getDbType());
        ProtocolCompat.detectOnce(config, logger);
        int failedChecks = 0;
        OracleTableDefinitions definitions = new OracleTableDefinitions();
        try (Connection conn = ConnectionFactory.create(config)) {
            for (CsvTable table : tables) {
                try {
                    String tableName = dialect.normalizeTableName(table.getTableName());
                    if (dialect.tableExists(conn, config.getSchema(), tableName)) {
                        continue;
                    }
                    reportMissingTableLocation(conn, dialect, config, tableName, logger);
                    CsvMeta meta = CsvMetaReader.read(table.getFile(), options.getEncoding());
                    if (!meta.isValid()) {
                        continue;
                    }
                    definitions.apply(table, meta, options.getEncoding(), dialect, config.getSchema(), logger);
                    ddlMap.put(table.getTableName(), DdlBuilder.build(dialect, config.getSchema(), tableName, meta));
                } catch (Exception error) {
                    failedChecks++;
                    logger.accept("[警告] 检查表 " + table.getTableName() + " 失败：" + error.getMessage());
                    // 单表元数据/采样失败只跳过该表；回滚避免 PostgreSQL 事务被标记为 aborted
                    rollbackQuietly(conn);
                }
            }
        }
        if (failedChecks > 0 && ddlMap.isEmpty()) {
            // 关键：检查阶段整体失败时不能让上层把"没有生成任何建表语句"误读成"表结构完整"
            logger.accept("[警告] " + failedChecks + " 张表的存在性检查失败，本次并未确认目标表是否存在；"
                    + "若确实缺表，导入时会在写入前直接报出原因");
        }
        return ddlMap;
    }

    /** 生成包含建表、扩长、提示和失败结果的逐表计划，预检不执行 DDL。 */
    public SchemaPlan prepareSchema(List<CsvTable> tables, ConnectionConfig config,
                                     ImportOptions options, Consumer<String> logger) throws Exception {
        Dialect dialect = DialectFactory.get(config.getDbType());
        ProtocolCompat.detectOnce(config, logger);
        try (Connection connection = ConnectionFactory.create(config)) {
            return SchemaPreparation.prepare(connection, dialect, config, options, tables, logger);
        }
    }

    /**
     * 执行导入（阻塞直到全部完成），应在后台线程调用。
     *
     * @param tables  勾选的表
     * @param ddlMap  允许执行的建表语句（表名 → DDL），仅在开启自动建表时使用
     */
    public ImportResult start(List<CsvTable> tables,
                              ConnectionConfig config,
                              ImportOptions options,
                              Map<String, String> ddlMap,
                              ProgressListener listener) throws Exception {
        if (options.isSyncColumnLengths() || options.isAddMissingColumns() || options.isSyncComments()) {
            throw new IllegalArgumentException("字段变更必须先生成并确认结构变更计划");
        }
        return startPrepared(tables, config, options, SchemaPlan.fromCreateSql(ddlMap), listener);
    }

    /** 执行已确认计划；检查失败或扩长失败的表在清表之前终止。 */
    public ImportResult startPrepared(List<CsvTable> tables, ConnectionConfig config, ImportOptions options,
                                       SchemaPlan plan, ProgressListener listener) throws Exception {
        cancelled.set(false);
        ImportResult result = new ImportResult();
        result.setTotalTables(tables.size());
        FailureRecorder recorder = new FailureRecorder();
        ExecutorService currentPool = null;
        ScheduledExecutorService ticker = null;
        try {
            // 失败明细实时落盘，程序异常中断也不会丢失；同时限量回显到界面日志
            try {
                recorder.openSink(FailureRecorder.defaultLogFile());
            } catch (Exception e) {
                listener.onLog("失败明细日志文件创建失败：" + e.getMessage());
                throw e;
            }
            final AtomicInteger failureLogCount = new AtomicInteger();
            recorder.setListener(record -> {
                int count = failureLogCount.incrementAndGet();
                if (count <= MAX_FAILURE_LOG_LINES) {
                    listener.onLog("[失败行] " + record.getTable() + " 第 " + record.getLineNo()
                            + " 行：" + record.getErrorCode() + " " + record.getMessage());
                } else if (count == MAX_FAILURE_LOG_LINES + 1) {
                    listener.onLog("失败行较多，界面仅回显前 " + MAX_FAILURE_LOG_LINES + " 条，完整明细见失败日志文件");
                }
            });
            Dialect dialect = DialectFactory.get(config.getDbType());
            // 先把"驱动参数协议是否可用"探明白，保证后面所有连接（含并发分片）都按同一口径打开
            ProtocolCompat.detectOnce(config, listener::onLog);
            long startedAt = System.currentTimeMillis();

            pool = Executors.newFixedThreadPool(options.getThreads(), new NamedThreadFactory());
            submittedTasks.clear();
            currentPool = pool;
            // 分片任务结果：主线程提交、进度 ticker 线程读取，需同步访问
            final Map<CsvTable, List<Future<Boolean>>> futures = Collections.synchronizedMap(new LinkedHashMap<>());
            // 已定稿（状态已落定）的表，避免重复定稿与重复回调
            final Set<CsvTable> finalizedTables = ConcurrentHashMap.newKeySet();
            ticker = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("progress-ticker"));
            ticker.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    // 某张表的全部分片结束后立即定稿，不必等其它表跑完才一起汇总
                    finalizeFinishedTables(futures, finalizedTables, cancelled, listener);
                    listener.onTick();
                }
            }, 500, 500, TimeUnit.MILLISECONDS);

            if (options.isFastMode()) {
                listener.onLog("极速模式已开启：" + describeFastMode(dialect));
            }

            OracleTableDefinitions definitions = new OracleTableDefinitions();
            try (Connection prepareConn = ConnectionFactory.create(config)) {
                prepareConnection = prepareConn;
                for (CsvTable table : tables) {
                    if (cancelled.get()) {
                        break;
                    }
                    table.resetProgress();
                    TableProgress progress = table.getProgress();
                    progress.start();
                    listener.onLog("开始导入 " + table.getTableName() + "（" + FileSizeUtil.formatSize(table.getSizeBytes()) + "）");
                    try {
                        CsvMeta meta = prepareTable(prepareConn, dialect, config, options, table, plan,
                                definitions, listener);
                        String tableName = dialect.normalizeTableName(table.getTableName());

                        warnOversizedTextIndex(prepareConn, config.getDbType(), config.getSchema(), tableName,
                                listener::onLog);

                        // 单表分片数：受并发线程数与单表上限约束；直接路径插入必须单会话
                        int shardCount = options.effectiveShardCount(
                                options.isFastMode() && dialect.fastLoadRequiresSingleSession());
                        List<ByteRange> ranges = SplitPlanner.plan(table.getFile(), shardCount,
                                options.getShardThresholdBytes());
                        table.setShardCount(ranges.size());
                        if (ranges.size() > 1) {
                            listener.onLog("大表并行：" + table.getTableName() + " 拆分为 " + ranges.size() + " 个分片");
                        }
                        List<Future<Boolean>> tableFutures = new ArrayList<>(ranges.size());
                        for (ByteRange range : ranges) {
                            if (cancelled.get()) {
                                break;
                            }
                            ShardImportTask task = new ShardImportTask(table, range, meta, config,
                                    dialect, options, progress, recorder, cancelled, listener::onLog);
                            submittedTasks.add(task);
                            try {
                                tableFutures.add(currentPool.submit(task));
                            } catch (RejectedExecutionException error) {
                                if (!cancelled.get()) {
                                    throw error;
                                }
                                break;
                            }
                        }
                        futures.put(table, tableFutures);
                        if (tableFutures.isEmpty() && cancelled.get()) {
                            progress.setStatus(TableStatus.CANCELLED);
                            progress.setMessage("已取消");
                            progress.stop();
                            listener.onTableFinished(table);
                        }
                    } catch (Exception error) {
                        // 准备阶段失败必须立刻回滚：PostgreSQL 会把出错的事务标记为 aborted，
                        // 不回滚的话后续所有表都会报 "current transaction is aborted"
                        rollbackQuietly(prepareConn);
                        progress.setStatus(cancelled.get() ? TableStatus.CANCELLED : TableStatus.FAILED);
                        progress.setMessage(cancelled.get() ? "已取消"
                                : "准备阶段失败：" + error.getMessage() + PlatformUtil.describeFileLockHint(error));
                        progress.stop();
                        recorder.record(table.getTableName(), 0, "PREPARE", progress.getMessage(), "");
                        // 失败/完成文案由 onTableFinished 统一输出，避免同一张表刷两条相同的失败日志
                        listener.onTableFinished(table);
                    }
                }
            } finally {
                prepareConnection = null;
            }

            currentPool.shutdown();
            if (cancelled.get()) {
                cancelQueuedTasks(currentPool);
            }
            awaitWorkers(currentPool);
            ticker.shutdownNow();
            awaitWorkers(ticker);

            finalizeFinishedTables(futures, finalizedTables, cancelled, listener);
            summarize(tables, futures, result, recorder);
            result.setElapsedMs(System.currentTimeMillis() - startedAt);
            result.setCancelled(cancelled.get());

            File failureLog = recorder.closeSink();
            if (failureLog != null) {
                result.setFailureLogFile(failureLog.getAbsolutePath());
                listener.onLog("失败明细已写入文件：" + failureLog.getAbsolutePath());
            } else if (recorder.getTotalCount() > 0) {
                listener.onLog("失败明细共 " + recorder.getTotalCount() + " 条（内存保留，可点「导出失败明细」保存）");
            }
            listener.onTick();
            listener.onAllFinished(result);
            return result;
        } finally {
            if (currentPool != null && !currentPool.isTerminated()) {
                cancel();
                awaitWorkers(currentPool);
            }
            if (ticker != null) {
                ticker.shutdownNow();
                awaitWorkers(ticker);
            }
            recorder.close();
            submittedTasks.clear();
            pool = null;
        }
    }

    /** 单表准备顺序固定为结构变更、结果验证、绑定刷新、清表；失败由调用方标记并跳过。 */
    private CsvMeta prepareTable(Connection prepareConn, Dialect dialect, ConnectionConfig config,
                                  ImportOptions options, CsvTable table, SchemaPlan plan,
                                  OracleTableDefinitions definitions, ProgressListener listener) throws Exception {
        SchemaPlan.Table tablePlan = plan.tables().get(table.getTableName());
        if ((options.isSyncColumnLengths() || options.isAddMissingColumns() || options.isSyncComments()) && tablePlan == null) {
            throw new SQLException("缺少该表已确认的结构预检结果");
        }
        if (tablePlan != null && !tablePlan.error().isEmpty()) {
            throw new SQLException("结构检查失败：" + tablePlan.error());
        }
        CsvMeta meta = CsvMetaReader.read(table.getFile(), options.getEncoding());
        if (!meta.isValid()) {
            throw new java.io.IOException("表头为空或解析失败");
        }
        String tableName = dialect.normalizeTableName(table.getTableName());

        boolean missing = !dialect.tableExists(prepareConn, config.getSchema(), tableName);
        boolean newlyCreated = false;
        SchemaComments.validateSession(prepareConn, tablePlan);
        if (missing && options.isAutoCreateTable() && tablePlan != null
                && tablePlan.createSql() != null) {
            definitions.apply(table, meta, options.getEncoding(), dialect, config.getSchema(), listener::onLog);
            ensureSchemaExists(prepareConn, dialect, config);
            executeDdl(prepareConn, tablePlan.createSql());
            newlyCreated = true;
            listener.onLog("已创建表 " + dialect.fullyQualified(config.getSchema(), tableName));
            missing = !dialect.tableExists(prepareConn, config.getSchema(), tableName);
        }
        if (missing) {
            // 目标表不存在时不要等到各分片写库报错，此处直接给出可操作原因
            throw new java.sql.SQLException(
                    describeMissingTable(prepareConn, dialect, config, tableName));
        }

        if (options.isSyncColumnLengths() || options.isAddMissingColumns() || options.isSyncComments()) {
            SchemaPreparation.applyChanges(prepareConn, dialect, config.getSchema(), tableName,
                    tablePlan, listener::onLog, cancelled::get);
        }
        if (cancelled.get()) throw new SQLException("导入已取消", "57014");
        TargetColumnTypes.apply(prepareConn, dialect, config.getSchema(), tableName, meta, newlyCreated);

        if (options.getClearMode() != ImportOptions.ClearMode.NONE) {
            clearTable(prepareConn, dialect, config, tableName, options.getClearMode());
            listener.onLog("已清空表 " + tableName + "（" + options.getClearMode().getLabel() + "）");
        }
        return meta;
    }

    /** 极速模式说明文案 */
    private static String describeFastMode(Dialect dialect) {
        switch (dialect.type()) {
            case POSTGRESQL:
            case KINGBASE:
                return dialect.type().getDisplayName() + " COPY 批量加载（失败自动降级为标准 INSERT）";
            case MYSQL:
                return "MySQL LOAD DATA LOCAL INFILE 批量加载（失败自动降级为标准 INSERT）";
            case ORACLE:
            case DAMENG:
                return dialect.type().getDisplayName() + " 直接路径插入 APPEND，单分片写入（失败自动降级为标准 INSERT）";
            default:
                return "标准批量写入";
        }
    }

    /**
     * 逐表检查：某张表的所有分片任务都结束后立即定稿状态并回调界面，
     * 让"完成"状态与行数实时可见，而不是等全部表跑完才一起汇总。
     */
    private void finalizeFinishedTables(Map<CsvTable, List<Future<Boolean>>> futures,
                                        Set<CsvTable> finalizedTables,
                                        AtomicBoolean cancelled,
                                        ProgressListener listener) {
        List<Map.Entry<CsvTable, List<Future<Boolean>>>> snapshot;
        synchronized (futures) {
            snapshot = new ArrayList<>(futures.entrySet());
        }
        for (Map.Entry<CsvTable, List<Future<Boolean>>> entry : snapshot) {
            finalizeTable(entry.getKey(), entry.getValue(), finalizedTables, cancelled, listener);
        }
    }

    /** 单表定稿；仍有分片未结束时退回，等下一次 tick 再判定 */
    private void finalizeTable(CsvTable table,
                               List<Future<Boolean>> tableFutures,
                               Set<CsvTable> finalizedTables,
                               AtomicBoolean cancelled,
                               ProgressListener listener) {
        if (tableFutures == null || tableFutures.isEmpty() || !finalizedTables.add(table)) {
            return;
        }
        boolean ok = true;
        String error = "";
        for (Future<Boolean> future : tableFutures) {
            if (!future.isDone()) {
                finalizedTables.remove(table);
                return;
            }
            try {
                Boolean value = future.get();
                if (value == null || !value) {
                    ok = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ok = false;
            } catch (Exception e) {
                ok = false;
                Throwable cause = e.getCause() == null ? e : e.getCause();
                error = String.valueOf(cause.getMessage());
            }
        }
        TableProgress progress = table.getProgress();
        if (cancelled.get() && progress.getStatus() == TableStatus.RUNNING) {
            progress.setStatus(TableStatus.CANCELLED);
            progress.setMessage("已取消");
        } else if (ok && progress.getFailedRows() == 0) {
            progress.setStatus(TableStatus.SUCCESS);
            if (progress.getMessage() == null || progress.getMessage().isEmpty()) {
                progress.setMessage("");
            }
        } else {
            progress.setStatus(TableStatus.FAILED);
            if (!error.isEmpty()) {
                progress.setMessage(error);
            } else if (progress.getFailedRows() > 0 && progress.getMessage().isEmpty()) {
                progress.setMessage("存在 " + progress.getFailedRows() + " 行导入失败，请查看失败明细");
            }
        }
        progress.stop();
        listener.onTableFinished(table);
    }

    /**
     * 汇总统计。
     *
     * <p>必须遍历「本次要导入的全部表」而不是只看已提交的分片：准备阶段就失败的表
     * （目标表不存在、CSV 解析失败等）根本没进 futures，若只统计 futures 就会出现
     * "共 2 张表，成功 0 张，失败 0 张"这种总数对不上的结果。
     */
    private void summarize(List<CsvTable> tables,
                           Map<CsvTable, List<Future<Boolean>>> futures,
                           ImportResult result,
                           FailureRecorder recorder) {
        int successTables = 0;
        int failedTables = 0;
        long successRows = 0;
        long failedRows = 0;

        for (CsvTable table : tables) {
            TableProgress progress = table.getProgress();
            // 取消过早而未启动的表保持「待导入」，不参与统计
            if (progress.getStatus() == TableStatus.PENDING) {
                continue;
            }
            List<Future<Boolean>> tableFutures = futures.get(table);
            if (tableFutures != null && !tableFutures.isEmpty()) {
                boolean ok = true;
                String error = "";
                for (Future<Boolean> future : tableFutures) {
                    try {
                        if (future.isCancelled()) {
                            continue;
                        }
                        Boolean value = future.get();
                        if (value == null || !value) {
                            ok = false;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ok = false;
                    } catch (CancellationException e) {
                        continue;
                    } catch (Exception e) {
                        ok = false;
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        error = String.valueOf(cause.getMessage());
                    }
                }
                // 已定稿为「已取消」的表不再改判为失败
                if (cancelled.get()
                        && (progress.getStatus() == TableStatus.RUNNING || progress.getStatus() == TableStatus.CANCELLED)) {
                    progress.setStatus(TableStatus.CANCELLED);
                    if (progress.getMessage() == null || progress.getMessage().isEmpty()) {
                        progress.setMessage("已取消");
                    }
                } else if (ok && progress.getFailedRows() == 0) {
                    progress.setStatus(TableStatus.SUCCESS);
                    if (progress.getMessage() == null || progress.getMessage().isEmpty()) {
                        progress.setMessage("");
                    }
                } else {
                    progress.setStatus(TableStatus.FAILED);
                    if (!error.isEmpty()) {
                        progress.setMessage(error);
                    } else if (progress.getFailedRows() > 0 && progress.getMessage().isEmpty()) {
                        progress.setMessage("存在 " + progress.getFailedRows() + " 行导入失败，请查看失败明细");
                    }
                }
            }
            // 其余情况沿用准备阶段已定稿的状态（失败并保持当时的原因）
            progress.stop();
            successRows += progress.getSuccessRows();
            failedRows += progress.getFailedRows();
            if (progress.getStatus() == TableStatus.SUCCESS) {
                successTables++;
            } else {
                failedTables++;
            }
        }

        result.setSuccessTables(successTables);
        result.setFailedTables(failedTables);
        result.setSuccessRows(successRows);
        result.setFailedRows(failedRows);
        result.setFailureRecordCount(recorder.getTotalCount());
        for (com.moral.model.FailureRecord record : recorder.snapshot()) {
            result.addFailure(record);
        }
    }

    /** 取消时清理线程池队列中的任务，避免汇总阶段无限等待未执行的 Future。 */
    private static void cancelQueuedTasks(ExecutorService executor) {
        for (Runnable queued : executor.shutdownNow()) {
            if (queued instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }

    /** 汇总前等待实际退出；线程中断时先取消任务，收尾后恢复中断标记。 */
    private void awaitWorkers(ExecutorService executor) {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
                cancel();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 自动建表前确保目标 schema 存在。
     * PostgreSQL 执行 {@code CREATE SCHEMA IF NOT EXISTS}；Oracle/达梦的 schema 即用户，
     * 方言返回 null 时为无操作。建 schema 失败（多为权限不足）时上抛，由准备阶段的异常分支记录。
     */
    private void ensureSchemaExists(Connection conn, Dialect dialect, ConnectionConfig config) throws SQLException {
        String sql = dialect.createSchemaIfNotExistsSql(config.getSchema());
        if (sql == null) {
            return;
        }
        SchemaPreparation.executeDdl(conn, sql);
    }

    /**
     * 静默回滚。PostgreSQL 出错后会把事务标记为 aborted，同一连接上的后续语句会全部被拒绝，
     * 因此准备阶段任何异常都必须先回滚，避免一张表失败连累后面所有表。
     */
    private static void rollbackQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            // 回滚失败不中断流程
        }
    }

    private void executeDdl(Connection conn, String ddl) throws java.sql.SQLException {
        SchemaPreparation.executeDdl(conn, ddl);
    }

    /**
     * 目标表不存在时的提示语：区分"整库都没有这张表"与"表在某处、只是不在写入目标 schema 下"。
     * 后者是本次故障的典型形态——界面 schema 填了 A，表实际建在 B，存在性检查稍放宽就会
     * 先报"表结构完整"，再在写库时报 {@code 关系 A.t 不存在}，排查成本很高。
     */
    private static String describeMissingTable(Connection conn, Dialect dialect,
                                               ConnectionConfig config, String tableName) {
        StringBuilder text = new StringBuilder("目标表 ")
                .append(dialect.fullyQualified(config.getSchema(), tableName)).append(" 不存在");
        List<String> otherSchemas = Collections.emptyList();
        try {
            otherSchemas = dialect.findTableInOtherSchemas(conn, config.getSchema(), tableName);
        } catch (SQLException ignore) {
            // 诊断失败不影响主流程
        }
        if (otherSchemas.isEmpty()) {
            text.append("：请确认「Schema」配置是否正确，或勾选「表不存在时自动建表」由工具创建");
        } else {
            text.append("：同名表存在于 ").append(String.join("、", otherSchemas))
                    .append("，当前目标是 ").append(config.getSchema())
                    .append("；若这些表就是要写入的表，请把「Schema」改成其中之一，"
                            + "否则勾选「表不存在时自动建表」在目标 schema 下新建");
        }
        return text.toString();
    }

    /** 生成缺失表 DDL 前的提示：把同名表实际所在 schema 说出来，便于及时发现 schema 填错 */
    private static void reportMissingTableLocation(Connection conn, Dialect dialect, ConnectionConfig config,
                                                   String tableName, Consumer<String> logger) {
        try {
            List<String> otherSchemas = dialect.findTableInOtherSchemas(conn, config.getSchema(), tableName);
            if (!otherSchemas.isEmpty()) {
                logger.accept("[警告] " + tableName + " 在目标 schema " + config.getSchema()
                        + " 下不存在，但同名表存在于 " + String.join("、", otherSchemas)
                        + "；若「Schema」填写有误请改正，否则将新建 " + config.getSchema() + "." + tableName);
            }
        } catch (SQLException ignore) {
            // 诊断失败不影响主流程
        }
    }

    /**
     * 导入前预检：PostgreSQL / 金仓的 btree 索引单条目上限约 2704 字节，
     * 长文本列（text、varchar(n>2704)）上建普通 btree 索引时，插入整篇 HTML 之类的长值会直接报
     * SQLSTATE 54000（program_limit_exceeded），且是整批失败、日志里只能看到被 SQL 回显淹没的消息。
     * 这里提前命中并给出可执行的修复建议，避免整张表空跑一轮。
     */
    private void warnOversizedTextIndex(Connection conn, DbType type, String schema, String tableName,
                                        Consumer<String> log) {
        if (type != DbType.POSTGRESQL && type != DbType.KINGBASE) {
            return;
        }
        // 统一加 pg_catalog 前缀：金仓部分模式（Oracle 兼容 / search_path 未含系统 schema）下裸表名不可用
        String sql = "SELECT c.relname, a.attname "
                + "FROM pg_catalog.pg_class tbl "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace "
                + "JOIN pg_catalog.pg_index idx ON idx.indrelid = tbl.oid "
                + "JOIN pg_catalog.pg_class c ON c.oid = idx.indexrelid "
                + "JOIN pg_catalog.pg_am am ON am.oid = c.relam "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = tbl.oid AND a.attnum = ANY(idx.indkey) "
                + "JOIN pg_catalog.pg_type t ON t.oid = a.atttypid "
                + "WHERE n.nspname::text = ? AND tbl.relname::text = ? AND am.amname = 'btree' AND idx.indisvalid "
                + "AND t.typname IN ('varchar', 'bpchar', 'text') "
                + "AND (a.atttypmod < 0 OR a.atttypmod - 4 > 2704)";
        if (type == DbType.KINGBASE) sql = sql.replace("pg_catalog.pg_", "sys_catalog.sys_");
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, schema == null || schema.isEmpty() ? "public" : schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    log.accept("[警告] " + tableName + "：列 " + rows.getString(2) + " 上存在 btree 索引 "
                            + rows.getString(1) + "，该列可容纳的长度超过 btree 单条目上限（约 2704 字节），"
                            + "写入长文本会整批报 54000(program_limit_exceeded)；"
                            + "建议先 DROP INDEX 或改为全文索引（GIN）后再导入");
                }
            }
        } catch (SQLException ignore) {
            // 预检失败（版本差异、权限不足等）不影响导入；
            // PG 系数据库已把事务标记为 aborted，必须回滚，否则这张表后续的清表/写入会报"当前事务被终止"
            rollbackQuietly(conn);
        }
    }

    private void clearTable(Connection conn, Dialect dialect, ConnectionConfig config,
                            String tableName, ImportOptions.ClearMode mode) throws java.sql.SQLException {
        String sql = mode == ImportOptions.ClearMode.TRUNCATE
                ? dialect.truncateSql(config.getSchema(), tableName)
                : dialect.deleteSql(config.getSchema(), tableName);
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        }
        try {
            conn.commit();
        } catch (java.sql.SQLException ignore) {
            // TRUNCATE 为隐式提交，忽略
        }
    }

    /** 线程命名，便于排查问题 */
    private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;

        NamedThreadFactory() {
            this("import-worker");
        }

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
