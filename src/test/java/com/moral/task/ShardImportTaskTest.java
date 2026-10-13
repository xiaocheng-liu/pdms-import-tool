package com.moral.task;

import com.moral.csv.ByteRange;
import com.moral.csv.CsvMeta;
import com.moral.db.PgDialect;
import com.moral.model.ColumnProfile;
import com.moral.model.ConnectionConfig;
import com.moral.model.CsvTable;
import com.moral.model.ImportOptions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** 分片取消、计数和看门狗回归测试。 */
public class ShardImportTaskTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** 等待日志不是数据进展，不能刷新超时基线。 */
    @Test
    public void watchdogLogDoesNotResetActivityTime() throws Exception {
        ShardImportTask task = task(new AtomicBoolean());
        long activity = System.currentTimeMillis() - 30000;
        setField(task, "lastActivityAt", activity);
        invoke(task, "reportStall", new Class<?>[0]);
        assertEquals(activity, field(task, "lastActivityAt"));
    }

    /** 取消后读取剩余文件不能提交尾批。 */
    @Test
    public void cancelledShardDoesNotReportSuccess() throws Exception {
        ShardImportTask task = task(new AtomicBoolean(true));
        BatchingWriterTest.JdbcFixture db = new BatchingWriterTest.JdbcFixture();
        assertEquals(false, invoke(task, "standardLoad",
                new Class<?>[]{Connection.class, String.class}, db.connection, "insert"));
        assertTrue(db.committed.isEmpty());
    }

    /** 最后一次 flush 失败也必须同步失败数量。 */
    @Test
    public void failedFinalBatchUpdatesProgress() throws Exception {
        ShardImportTask task = task(new AtomicBoolean());
        BatchingWriterTest.JdbcFixture db = new BatchingWriterTest.JdbcFixture();
        db.batchError = new SQLException("offline", "08006");
        assertEquals(false, invoke(task, "standardLoad",
                new Class<?>[]{Connection.class, String.class}, db.connection, "insert"));
        assertEquals(1, task.getTable().getProgress().getFailedRows());
    }

    /** 提交结果不明确时，快速模式不能重读分片。 */
    @Test
    public void uncertainFastCommitDoesNotFallBack() throws Exception {
        ShardImportTask task = task(new AtomicBoolean());
        BatchingWriterTest.JdbcFixture db = new BatchingWriterTest.JdbcFixture();
        db.commitError = new SQLException("commit disconnected", "08006");
        assertEquals(false, invoke(task, "fastPathInsert",
                new Class<?>[]{Connection.class}, db.connection));
    }

    /** 二次回滚断连不能覆盖执行批次的原始错误，也不能降级重放。 */
    @Test
    public void fastRollbackFailurePreservesOriginalError() throws Exception {
        ShardImportTask task = task(new AtomicBoolean());
        BatchingWriterTest.JdbcFixture db = new BatchingWriterTest.JdbcFixture();
        db.batchError = new SQLException("original batch failure", "08006", 7001);
        db.rollbackError = new SQLException("connection closed", "08003", 6060);
        var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(task, "fastPathInsert", new Class<?>[]{Connection.class}, db.connection));
        assertSame(db.batchError, thrown.getCause());
        assertTrue(db.batchError.getSuppressed().length > 0);
        assertEquals(0, db.replayCalls);
        FailureRecorder recorder = (FailureRecorder) field(task, "recorder");
        assertTrue(recorder.snapshot().getFirst().getMessage().contains("original batch failure"));
    }

    /** COPY 没有 Statement，超时也必须触发连接级终止。 */
    @Test
    public void stalledCopyAbortsConnectionWithoutStatement() throws Exception {
        ShardImportTask task = task(new AtomicBoolean());
        CountDownLatch aborted = new CountDownLatch(1);
        Connection connection = BatchingWriterTest.proxy(Connection.class, (method, args) -> {
            if ("abort".equals(method)) {
                aborted.countDown();
            }
            return null;
        });
        setField(task, "activeConnection", connection);
        setField(task, "lastActivityAt", System.currentTimeMillis() - 130000);
        invoke(task, "reportStall", new Class<?>[0]);
        assertTrue(aborted.await(5, TimeUnit.SECONDS));
        assertTrue(((AtomicBoolean) field(task, "autoCancelled")).get());
    }

    /** 驱动不支持 abort 时退回关闭连接，停止入口本身不能阻塞。 */
    @Test
    public void stopFallsBackToAsynchronousClose() throws Exception {
        ShardImportTask task = task(new AtomicBoolean(true));
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        Connection connection = BatchingWriterTest.proxy(Connection.class, (method, args) -> {
            if ("abort".equals(method)) {
                throw new java.sql.SQLFeatureNotSupportedException();
            }
            if ("close".equals(method)) {
                closing.countDown();
                release.await(5, TimeUnit.SECONDS);
                closed.countDown();
            }
            return null;
        });
        try {
            setField(task, "activeConnection", connection);
            task.cancelDatabaseWork();
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertEquals(1, closed.getCount());
        } finally {
            release.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
        }
    }

    /** 使用真实的小 CSV，数据库边界由事务替身代替。 */
    private ShardImportTask task(AtomicBoolean cancelled) throws Exception {
        File file = temporary.newFile("sample.csv");
        Files.writeString(file.toPath(), "value\ngood\n");
        CsvTable table = new CsvTable(file);
        return new ShardImportTask(table, new ByteRange(0, file.length(), 0, 1),
                new CsvMeta(List.of("value"), List.of(new ColumnProfile("value"))),
                new ConnectionConfig(), new PgDialect(), new ImportOptions(),
                table.getProgress(), new FailureRecorder(), cancelled, message -> { });
    }

    /** 调用内部流程，不建立业务数据库连接。 */
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    /** 注入看门狗时间基线。 */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 读取看门狗时间基线。 */
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
