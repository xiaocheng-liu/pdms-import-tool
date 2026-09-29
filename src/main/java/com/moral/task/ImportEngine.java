package com.moral.task;

import com.moral.csv.ByteRange;
import com.moral.csv.CsvMeta;
import com.moral.csv.CsvMetaReader;
import com.moral.csv.DdlBuilder;
import com.moral.csv.SplitPlanner;
import com.moral.db.ConnectionFactory;
import com.moral.db.Dialect;
import com.moral.db.DialectFactory;
import com.moral.model.ConnectionConfig;
import com.moral.model.CsvTable;
import com.moral.model.ImportOptions;
import com.moral.model.ImportResult;
import com.moral.model.TableProgress;
import com.moral.model.TableStatus;
import com.moral.util.FileSizeUtil;
import com.moral.util.PlatformUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
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

    /**
     * 停止导入：先中断各任务正在挂起的数据库操作（JDBC 的 executeBatch/commit 不响应线程中断，
     * 必须显式 statement.cancel()），再关闭线程池。
     */
    public void cancel() {
        cancelled.set(true);
        for (ShardImportTask task : submittedTasks) {
            try {
                task.cancelDatabaseWork();
            } catch (RuntimeException ignore) {
                // 单个任务取消失败不影响其它任务
            }
        }
        ExecutorService current = pool;
        if (current != null) {
            current.shutdownNow();
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
        Map<String, String> ddlMap = new LinkedHashMap<>();
        Dialect dialect = DialectFactory.get(config.getDbType());
        try (Connection conn = ConnectionFactory.create(config)) {
            for (CsvTable table : tables) {
                try {
                    String tableName = dialect.normalizeTableName(table.getTableName());
                    if (dialect.tableExists(conn, config.getSchema(), tableName)) {
                        continue;
                    }
                    CsvMeta meta = CsvMetaReader.read(table.getFile(), options.getEncoding());
                    if (!meta.isValid()) {
                        continue;
                    }
                    ddlMap.put(table.getTableName(), DdlBuilder.build(dialect, config.getSchema(), tableName, meta));
                } catch (Exception error) {
                    // 单表元数据/采样失败只跳过该表；回滚避免 PostgreSQL 事务被标记为 aborted
                    rollbackQuietly(conn);
                }
            }
        }
        return ddlMap;
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
        cancelled.set(false);
        ImportResult result = new ImportResult();
        result.setTotalTables(tables.size());
        FailureRecorder recorder = new FailureRecorder();
        // 失败明细实时落盘，程序异常中断也不会丢失；同时限量回显到界面日志
        try {
            recorder.openSink(FailureRecorder.defaultLogFile());
        } catch (Exception e) {
            listener.onLog("失败明细日志文件创建失败：" + e.getMessage());
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
        long startedAt = System.currentTimeMillis();

        pool = Executors.newFixedThreadPool(options.getThreads(), new NamedThreadFactory());
        ExecutorService currentPool = pool;
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("progress-ticker"));
        ticker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                listener.onTick();
            }
        }, 500, 500, TimeUnit.MILLISECONDS);

        if (options.isFastMode()) {
            listener.onLog("极速模式已开启：" + describeFastMode(dialect));
        }

        Map<CsvTable, List<Future<Boolean>>> futures = new LinkedHashMap<>();
        try (Connection prepareConn = ConnectionFactory.create(config)) {
            for (CsvTable table : tables) {
                if (cancelled.get()) {
                    break;
                }
                table.resetProgress();
                TableProgress progress = table.getProgress();
                progress.start();
                listener.onLog("开始导入 " + table.getTableName() + "（" + FileSizeUtil.formatSize(table.getSizeBytes()) + "）");
                try {
                    CsvMeta meta = CsvMetaReader.read(table.getFile(), options.getEncoding());
                    if (!meta.isValid()) {
                        throw new java.io.IOException("表头为空或解析失败");
                    }
                    String tableName = dialect.normalizeTableName(table.getTableName());

                    if (options.isAutoCreateTable() && ddlMap != null && ddlMap.containsKey(table.getTableName())
                            && !dialect.tableExists(prepareConn, config.getSchema(), tableName)) {
                        ensureSchemaExists(prepareConn, dialect, config);
                        executeDdl(prepareConn, ddlMap.get(table.getTableName()));
                        listener.onLog("已创建表 " + dialect.fullyQualified(config.getSchema(), tableName));
                    }

                    if (options.getClearMode() != ImportOptions.ClearMode.NONE) {
                        clearTable(prepareConn, dialect, config, tableName, options.getClearMode());
                        listener.onLog("已清空表 " + tableName + "（" + options.getClearMode().getLabel() + "）");
                    }

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
                        ShardImportTask task = new ShardImportTask(table, range, meta, config,
                                dialect, options, progress, recorder, cancelled, listener::onLog);
                        submittedTasks.add(task);
                        tableFutures.add(currentPool.submit(task));
                    }
                    futures.put(table, tableFutures);
                } catch (Exception error) {
                    // 准备阶段失败必须立刻回滚：PostgreSQL 会把出错的事务标记为 aborted，
                    // 不回滚的话后续所有表都会报 "current transaction is aborted"
                    rollbackQuietly(prepareConn);
                    progress.setStatus(TableStatus.FAILED);
                    progress.setMessage("准备阶段失败：" + error.getMessage() + PlatformUtil.describeFileLockHint(error));
                    progress.stop();
                    recorder.record(table.getTableName(), 0, "PREPARE", progress.getMessage(), "");
                    listener.onLog("[失败] " + table.getTableName() + "：" + progress.getMessage());
                    listener.onTableFinished(table);
                }
            }
        }

        currentPool.shutdown();
        while (!currentPool.awaitTermination(1, TimeUnit.SECONDS)) {
            if (cancelled.get()) {
                currentPool.shutdownNow();
                break;
            }
        }
        ticker.shutdownNow();

        summarize(futures, result, recorder, options);
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
    }

    /** 极速模式说明文案 */
    private static String describeFastMode(Dialect dialect) {
        switch (dialect.type()) {
            case POSTGRESQL:
                return "PostgreSQL COPY 批量加载（失败自动降级为标准 INSERT）";
            case ORACLE:
            case DAMENG:
                return dialect.type().getDisplayName() + " 直接路径插入 APPEND，单分片写入（失败自动降级为标准 INSERT）";
            default:
                return "标准批量写入";
        }
    }

    private void summarize(Map<CsvTable, List<Future<Boolean>>> futures,
                           ImportResult result,
                           FailureRecorder recorder,
                           ImportOptions options) {
        int successTables = 0;
        int failedTables = 0;
        long successRows = 0;
        long failedRows = 0;

        for (Map.Entry<CsvTable, List<Future<Boolean>>> entry : futures.entrySet()) {
            CsvTable table = entry.getKey();
            TableProgress progress = table.getProgress();
            boolean ok = true;
            String error = "";
            for (Future<Boolean> future : entry.getValue()) {
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
            if (cancelled.get() && progress.getStatus() == TableStatus.RUNNING) {
                progress.setStatus(TableStatus.CANCELLED);
                progress.setMessage("已取消");
            } else if (ok) {
                progress.setStatus(TableStatus.SUCCESS);
                if (progress.getMessage() == null || progress.getMessage().isEmpty()) {
                    progress.setMessage("");
                }
            } else {
                progress.setStatus(TableStatus.FAILED);
                if (!error.isEmpty()) {
                    progress.setMessage(error);
                }
            }
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
        for (com.moral.model.FailureRecord record : recorder.snapshot()) {
            result.addFailure(record);
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
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        }
        try {
            conn.commit();
        } catch (SQLException ignore) {
            // 部分库 DDL 为隐式提交，忽略
        }
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
        try (Statement statement = conn.createStatement()) {
            statement.execute(ddl);
        }
        try {
            conn.commit();
        } catch (java.sql.SQLException ignore) {
            // DDL 多为隐式提交，忽略
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
