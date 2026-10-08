package com.moral.model;

/**
 * 单表导入进度，由多个分片任务并发累加，使用 volatile 字段保证可见性。
 */
public class TableProgress {

    private final String tableName;
    private final long bytesTotal;

    private volatile TableStatus status = TableStatus.PENDING;
    private volatile long bytesDone;
    private volatile long rowsDone;
    private volatile long successRows;
    private volatile long failedRows;
    private volatile long startAt;
    private volatile long elapsedMs;
    private volatile String message = "";
    private volatile int shardCount = 1;
    /** 各分片"本次尝试"已读字节的绝对值，用于避免重试时重复累加 */
    private long[] shardBytes = new long[1];

    public TableProgress(String tableName, long bytesTotal) {
        this.tableName = tableName;
        this.bytesTotal = bytesTotal;
    }

    public String getTableName() {
        return tableName;
    }

    public long getBytesTotal() {
        return bytesTotal;
    }

    public TableStatus getStatus() {
        return status;
    }

    public void setStatus(TableStatus status) {
        this.status = status;
    }

    public long getBytesDone() {
        return bytesDone;
    }

    public long getRowsDone() {
        return rowsDone;
    }

    public long getSuccessRows() {
        return successRows;
    }

    public long getFailedRows() {
        return failedRows;
    }

    public long getElapsedMs() {
        if (status == TableStatus.RUNNING && startAt > 0) {
            return System.currentTimeMillis() - startAt;
        }
        return elapsedMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message == null ? "" : message;
    }

    public int getShardCount() {
        return shardCount;
    }

    public synchronized void setShardCount(int shardCount) {
        this.shardCount = Math.max(1, shardCount);
        if (shardBytes.length < this.shardCount) {
            shardBytes = java.util.Arrays.copyOf(shardBytes, this.shardCount);
        }
    }

    public void start() {
        this.startAt = System.currentTimeMillis();
        this.status = TableStatus.RUNNING;
    }

    public void stop() {
        if (startAt > 0) {
            this.elapsedMs = System.currentTimeMillis() - startAt;
        }
    }

    /**
     * 上报某个分片"本次尝试"已读取的字节数（绝对值，从 0 起算）。
     *
     * <p>用绝对值覆盖而不是累加：极速模式（COPY / LOAD DATA）失败会降级为标准写入并从头重读该分片，
     * 累加会把同一段字节统计两遍，导致总进度出现 198% 这类超出 100% 的数字。
     */
    public synchronized void reportBytes(int shardIndex, long bytes) {
        if (bytes < 0) {
            return;
        }
        int index = Math.max(0, shardIndex);
        if (index >= shardBytes.length) {
            shardBytes = java.util.Arrays.copyOf(shardBytes, index + 1);
        }
        shardBytes[index] = bytes;
        long sum = 0;
        for (long value : shardBytes) {
            sum += value;
        }
        // 兜底：单表已读字节永远不超过文件体积
        this.bytesDone = bytesTotal > 0 ? Math.min(bytesTotal, sum) : 0;
    }

    public synchronized void addRows(long rows) {
        this.rowsDone += rows;
    }

    public synchronized void addSuccess(long rows) {
        this.successRows += rows;
    }

    public synchronized void addFailed(long rows) {
        this.failedRows += rows;
    }

    /** 0~100 的百分比 */
    public int getPercent() {
        if (status == TableStatus.SUCCESS) {
            return 100;
        }
        if (bytesTotal <= 0) {
            return 0;
        }
        long percent = bytesDone * 100L / bytesTotal;
        return (int) Math.max(0, Math.min(100, percent));
    }
}
