package com.moral.task;

import com.moral.model.ColumnProfile;
import com.moral.model.InferredType;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Exercises transaction boundaries without a database server. */
public class BatchingWriterTest {

    @Test
    public void replayDoesNotCountRowsBeforeCommit() throws Exception {
        JdbcFixture db = new JdbcFixture();
        db.batchError = new SQLException("bad batch", "23000");
        db.commitError = new SQLException("commit disconnected", "08006");
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        writer.write(List.of("good"), 2);
        assertThrows(SQLException.class, writer::flush);
        assertEquals(0, writer.getSuccessRows());
    }

    @Test
    public void replayKeepsGoodRowsAfterFiftyBadRows() throws Exception {
        JdbcFixture db = new JdbcFixture();
        db.batchError = new SQLException("bad batch", "23000");
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        for (int i = 0; i < 60; i++) {
            writer.write(List.of("bad"), i + 2);
        }
        writer.write(List.of("good"), 62);
        writer.flush();
        assertEquals(List.of("good"), db.committed);
        assertEquals(1, writer.getSuccessRows());
        assertEquals(60, writer.getFailedRows());
        assertEquals(60, db.recorder.snapshot().size());
    }

    @Test
    public void environmentFailureStopsEvenWhenSkippingBadRows() throws Exception {
        JdbcFixture db = new JdbcFixture();
        db.batchError = new SQLException("permission denied", "42501");
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        writer.write(List.of("good"), 2);
        assertThrows(SQLException.class, writer::flush);
        assertEquals(0, writer.getSuccessRows());
    }

    @Test
    public void replayRollbackDoesNotLeavePhantomSuccesses() throws Exception {
        JdbcFixture db = new JdbcFixture();
        db.batchError = new SQLException("bad batch", "23000");
        db.fatalValue = "offline";
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        writer.write(List.of("good"), 2);
        writer.write(List.of("offline"), 3);
        assertThrows(SQLException.class, writer::flush);
        assertEquals(0, writer.getSuccessRows());
        assertTrue(db.committed.isEmpty());
    }

    @Test
    public void decimalBindingPreservesAllDigits() throws Exception {
        JdbcFixture db = new JdbcFixture();
        BatchingWriter writer = db.writer(InferredType.DOUBLE, true);
        writer.write(List.of("12345678901234567890.123456789012345678"), 2);
        assertEquals(new BigDecimal("12345678901234567890.123456789012345678"), db.bound);
    }

    @Test
    public void commitFailureIsNeverReplayedEvenWithDataSqlState() throws Exception {
        JdbcFixture db = new JdbcFixture();
        db.commitError = new SQLException("deferred constraint or unknown outcome", "23000");
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        writer.write(List.of("good"), 2);
        assertThrows(SQLException.class, writer::flush);
        assertEquals(0, db.replayCalls);
        assertEquals(0, writer.getSuccessRows());
    }

    @Test
    public void malformedRowHonorsStopOnError() {
        JdbcFixture db = new JdbcFixture();
        BatchingWriter writer = db.writer(InferredType.STRING, false);
        assertThrows(SQLException.class,
                () -> writer.skipMalformedRow(2, "column count mismatch", List.of("one", "two")));
    }

    @Test
    public void normalBatchCountsOnlyCommittedRows() throws Exception {
        JdbcFixture db = new JdbcFixture();
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        writer.write(List.of("00123"), 2);
        assertEquals(0, writer.getSuccessRows());
        writer.flush();
        assertEquals(List.of("00123"), db.committed);
        assertEquals(1, writer.getSuccessRows());
    }

    /** 无保存点时逐行提交，坏行不能撤销之前的好行。 */
    @Test
    public void replayWithoutSavepointsCountsCommittedRows() throws Exception {
        JdbcFixture db = new JdbcFixture();
        db.savepoints = false;
        db.batchError = new SQLException("bad batch", "23000");
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        writer.write(List.of("first"), 2);
        writer.write(List.of("bad"), 3);
        writer.write(List.of("last"), 4);
        writer.flush();
        assertEquals(List.of("first", "last"), db.committed);
        assertEquals(2, writer.getSuccessRows());
        assertEquals(1, writer.getFailedRows());
    }

    /** 后续重放失败只回滚未提交部分。 */
    @Test
    public void replayFailureRetainsOnlyPreviouslyCommittedRows() throws Exception {
        JdbcFixture db = new JdbcFixture();
        db.batchError = new SQLException("bad batch", "23000");
        db.fatalValue = "offline";
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        for (int i = 0; i < 101; i++) {
            writer.write(List.of("good"), i + 2);
        }
        writer.write(List.of("offline"), 103);
        assertThrows(SQLException.class, writer::flush);
        assertEquals(100, db.committed.size());
        assertEquals(100, writer.getSuccessRows());
        assertEquals(2, writer.getFailedRows());
    }

    /** 回滚失败后不能开始重放。 */
    @Test
    public void failedRollbackPreventsReplay() throws Exception {
        JdbcFixture db = new JdbcFixture();
        db.batchError = new SQLException("bad batch", "23000");
        db.rollbackError = new SQLException("rollback disconnected", "08006");
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        writer.write(List.of("good"), 2);
        assertThrows(SQLException.class, writer::flush);
        assertEquals(0, db.replayCalls);
    }

    /** 取消后不能把缓存的尾批提交。 */
    @Test
    public void cancellationPreventsPendingBatchCommit() throws Exception {
        JdbcFixture db = new JdbcFixture();
        BatchingWriter writer = db.writer(InferredType.STRING, true);
        writer.write(List.of("good"), 2);
        writer.cancelCurrent();
        assertThrows(SQLException.class, writer::flush);
        assertTrue(db.committed.isEmpty());
    }

    /** 长字段失败明细必须保留完整 CSV。 */
    @Test
    public void failureRetainsFullRawRecord() throws Exception {
        JdbcFixture db = new JdbcFixture();
        BatchingWriter writer = db.writer(InferredType.LONG, true);
        String value = "x".repeat(5000) + "\n\"end\"";
        writer.write(List.of(value), 2);
        assertEquals("\"" + value.replace("\"", "\"\"") + "\"",
                db.recorder.snapshot().getFirst().getRawLine());
    }

    /** 采样后出现前导零不能静默转成数字。 */
    @Test
    public void lateLeadingZeroValuesAreRecordedInsteadOfNormalized() throws Exception {
        for (InferredType type : List.of(InferredType.LONG, InferredType.DECIMAL)) {
            JdbcFixture db = new JdbcFixture();
            BatchingWriter writer = db.writer(type, true);
            writer.write(List.of("00123"), 5002);
            writer.flush();
            assertTrue(db.committed.isEmpty());
            assertEquals(1, writer.getFailedRows());
            assertEquals("\"00123\"", db.recorder.snapshot().getFirst().getRawLine());
        }
    }

    /** 驱动取消调用阻塞时，界面发起取消的线程仍应立即返回。 */
    @Test
    public void cancellationDoesNotBlockCallerOnDriver() throws Exception {
        JdbcFixture db = new JdbcFixture();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        db.cancelAction = () -> {
            entered.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        };
        java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            BatchingWriter writer = db.writer(InferredType.STRING, true);
            java.util.concurrent.Future<?> cancellation = caller.submit(writer::cancelCurrent);
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            cancellation.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } finally {
            release.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    /** Minimal transactional JDBC double: rollback discards pending writes. */
    static final class JdbcFixture {
        final FailureRecorder recorder = new FailureRecorder();
        final List<Object> committed = new ArrayList<>();
        final List<Object> transaction = new ArrayList<>();
        final List<Object> batch = new ArrayList<>();
        SQLException batchError;
        SQLException commitError;
        SQLException rollbackError;
        String fatalValue;
        Object bound;
        int replayCalls;
        boolean savepoints = true;
        Runnable cancelAction = () -> { };
        final PreparedStatement statement = proxy(PreparedStatement.class, (method, args) -> {
            switch (method) {
                case "cancel":
                    cancelAction.run();
                    return null;
                case "setString", "setLong", "setDouble", "setBigDecimal", "setObject":
                    bound = args[1];
                    return null;
                case "addBatch":
                    batch.add(bound);
                    return null;
                case "clearBatch":
                    batch.clear();
                    return null;
                case "executeBatch":
                    if (batchError != null) {
                        throw batchError;
                    }
                    transaction.addAll(batch);
                    return new int[batch.size()];
                case "executeUpdate":
                    replayCalls++;
                    if ("bad".equals(bound)) {
                        throw new SQLException("invalid row", "23000");
                    }
                    if (bound.equals(fatalValue)) {
                        throw new SQLException("connection gone", "08006");
                    }
                    transaction.add(bound);
                    return 1;
                default:
                    return null;
            }
        });
        final Connection connection = proxy(Connection.class, (method, args) -> {
            switch (method) {
                case "prepareStatement":
                    return statement;
                case "commit":
                    if (commitError != null) {
                        throw commitError;
                    }
                    committed.addAll(transaction);
                    transaction.clear();
                    return null;
                case "rollback":
                    if (rollbackError != null) {
                        throw rollbackError;
                    }
                    int size = args == null || args.length == 0 ? 0 : ((Savepoint) args[0]).getSavepointId();
                    transaction.subList(size, transaction.size()).clear();
                    return null;
                case "setSavepoint":
                    if (!savepoints) {
                        throw new java.sql.SQLFeatureNotSupportedException();
                    }
                    int position = transaction.size();
                    return proxy(Savepoint.class, (name, ignored) -> position);
                default:
                    return null;
            }
        });

        /** Creates a writer whose batch is explicitly flushed by the test. */
        BatchingWriter writer(InferredType type, boolean continueOnError) {
            ColumnProfile profile = new ColumnProfile("value");
            profile.setType(type);
            return new BatchingWriter(connection, statement, List.of(profile), 1000,
                    true, continueOnError, recorder, "sample", 0);
        }
    }

    /** Creates only the JDBC boundary needed by these tests. */
    static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> invocation.call(method.getName(), args)));
    }

    @FunctionalInterface
    interface Invocation {
        Object call(String method, Object[] args) throws Exception;
    }
}
