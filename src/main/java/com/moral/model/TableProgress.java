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

    public void setShardCount(int shardCount) {
        this.shardCount = Math.max(1, shardCount);
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

    public synchronized void addBytes(long bytes) {
        this.bytesDone += bytes;
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
