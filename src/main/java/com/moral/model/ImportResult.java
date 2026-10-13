package com.moral.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次导入的汇总结果。
 */
public class ImportResult {

    private final List<FailureRecord> failures = new ArrayList<>();
    private int totalTables;
    private int successTables;
    private int failedTables;
    private long successRows;
    private long failedRows;
    private long elapsedMs;
    private boolean cancelled;
    /** 实时写入的失败明细日志文件（没有失败时为 null） */
    private String failureLogFile;
    private long failureRecordCount;

    /** 完整明细总数，独立于内存缓存条数和失败数据行数。 */
    public long getFailureRecordCount() {
        return failureRecordCount;
    }

    /** 汇总落盘与内存共同记录的明细总数。 */
    public void setFailureRecordCount(long failureRecordCount) {
        this.failureRecordCount = failureRecordCount;
    }

    public synchronized void addFailure(FailureRecord record) {
        failures.add(record);
    }

    public List<FailureRecord> getFailures() {
        return failures;
    }

    public int getTotalTables() {
        return totalTables;
    }

    public void setTotalTables(int totalTables) {
        this.totalTables = totalTables;
    }

    public int getSuccessTables() {
        return successTables;
    }

    public void setSuccessTables(int successTables) {
        this.successTables = successTables;
    }

    public int getFailedTables() {
        return failedTables;
    }

    public void setFailedTables(int failedTables) {
        this.failedTables = failedTables;
    }

    public long getSuccessRows() {
        return successRows;
    }

    public void setSuccessRows(long successRows) {
        this.successRows = successRows;
    }

    public long getFailedRows() {
        return failedRows;
    }

    public void setFailedRows(long failedRows) {
        this.failedRows = failedRows;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public String getFailureLogFile() {
        return failureLogFile;
    }

    public void setFailureLogFile(String failureLogFile) {
        this.failureLogFile = failureLogFile;
    }
}
