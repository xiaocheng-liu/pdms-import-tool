package com.moral.task;

import com.moral.model.CsvTable;
import com.moral.model.ImportResult;
import com.moral.model.TableStatus;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** 导入取消和最终状态回归测试，不连接业务数据库。 */
public class ImportEngineTest {

    /** 只结束队列中的 Future，运行任务必须等真实退出。 */
    @Test
    public void cancelCompletesQueuedFutureButDoesNotCompleteRunningFuture() throws Exception {
        ImportEngine engine = new ImportEngine();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        setField(engine, "pool", pool);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> running = pool.submit(() -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return true;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<Boolean> queued = pool.submit(() -> true);
            engine.cancel();
            assertTrue("排队 Future 必须结束", queued.isDone());
            assertTrue(queued.isCancelled());
            assertFalse("运行任务仍未退出", running.isDone());
            assertFalse(pool.isTerminated());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 分片返回成功也不能掩盖失败行。 */
    @Test
    public void finalizeMarksFailedRowsAsFailed() throws Exception {
        ImportEngine engine = new ImportEngine();
        CsvTable table = failedTable();
        Method method = ImportEngine.class.getDeclaredMethod("finalizeTable",
                CsvTable.class, List.class, Set.class, AtomicBoolean.class, ProgressListener.class);
        method.setAccessible(true);
        method.invoke(engine, table, successfulFutures(), new HashSet<CsvTable>(),
                new AtomicBoolean(), new SilentListener());
        assertEquals(TableStatus.FAILED, table.getProgress().getStatus());
    }

    /** 最终汇总不能把失败行表重新改成成功。 */
    @Test
    public void summarizeMarksFailedRowsAsFailed() throws Exception {
        ImportEngine engine = new ImportEngine();
        CsvTable table = failedTable();
        ImportResult result = new ImportResult();
        Method method = ImportEngine.class.getDeclaredMethod("summarize",
                List.class, Map.class, ImportResult.class, FailureRecorder.class);
        method.setAccessible(true);
        FailureRecorder recorder = new FailureRecorder();
        recorder.record("sample", 1, "22000", "bad", "value");
        method.invoke(engine, Collections.singletonList(table),
                Collections.singletonMap(table, successfulFutures()), result, recorder);
        assertEquals(1, result.getFailureRecordCount());
        assertEquals(TableStatus.FAILED, table.getProgress().getStatus());
        assertEquals(0, result.getSuccessTables());
        assertEquals(1, result.getFailedTables());
        assertEquals(2, result.getFailedRows());
    }

    /** 被移出队列的任务完成后，收尾仍须等待运行任务退出。 */
    @Test
    public void shutdownWaitsForRunningTaskCleanup() throws Exception {
        ImportEngine engine = new ImportEngine();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        setField(engine, "pool", pool);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.submit(() -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
        });
        ExecutorService observer = Executors.newSingleThreadExecutor();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            engine.cancel();
            Future<?> waiting = observer.submit(() -> {
                try {
                    Method method = ImportEngine.class.getDeclaredMethod("awaitWorkers", ExecutorService.class);
                    method.setAccessible(true);
                    method.invoke(engine, pool);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> waiting.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            waiting.get(5, TimeUnit.SECONDS);
            assertTrue(pool.isTerminated());
        } finally {
            release.countDown();
            pool.shutdownNow();
            observer.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(observer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 构造带失败行的运行中表。 */
    private static CsvTable failedTable() {
        CsvTable table = new CsvTable(new File("engine-test.csv"));
        table.getProgress().start();
        table.getProgress().addFailed(2);
        return table;
    }

    /** 模拟分片正常返回。 */
    private static List<Future<Boolean>> successfulFutures() {
        return Collections.singletonList(java.util.concurrent.CompletableFuture.completedFuture(true));
    }

    /** 注入真实线程池。 */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 模拟不响应中断、需要完成资源收尾的任务。 */
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 空回调。 */
    private static class SilentListener implements ProgressListener {
        public void onLog(String message) { }
        public void onTick() { }
        public void onTableFinished(CsvTable table) { }
        public void onAllFinished(ImportResult result) { }
    }
}
