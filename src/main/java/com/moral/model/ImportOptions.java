package com.moral.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 导入选项（界面可配置）。
 */
public class ImportOptions {

    /** 导入前清表方式 */
    public enum ClearMode {
        NONE("不清空"),
        TRUNCATE("TRUNCATE"),
        DELETE("DELETE");

        private final String label;

        ClearMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** 并发线程数（同时等于最大数据库连接数） */
    private int threads = 6;
    /** 每批提交行数 */
    private int batchSize = 5000;
    /** 大表分片阈值（字节），超过则拆分并行导入 */
    private long shardThresholdBytes = 200L * 1024 * 1024;
    /** 单表最大分片数：实际分片数 = min(并发线程数, 该值) */
    private int maxShardsPerTable = 8;
    /** 极速模式：PG 使用 COPY、Oracle/达梦使用直接路径插入；失败时自动降级为标准模式 */
    private boolean fastMode = false;
    /** CSV 编码 */
    private Charset encoding = StandardCharsets.UTF_8;
    /** 空字符串写库时转 NULL */
    private boolean emptyAsNull = true;
    /** 导入前清表方式 */
    private ClearMode clearMode = ClearMode.NONE;
    /** 表不存在时自动建表 */
    private boolean autoCreateTable = false;
    /** 单批失败时跳过并继续 */
    private boolean continueOnError = true;
    /**
     * 写入超时（秒）：单个批次执行/提交超过该时间且无进展时，看门狗自动取消当前批次。
     * 0 表示关闭自动中断（仅保留连接层网络超时兜底）。
     */
    private int writeTimeoutSeconds = 120;

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = Math.max(1, Math.min(32, threads));
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, Math.min(50000, batchSize));
    }

    public long getShardThresholdBytes() {
        return shardThresholdBytes;
    }

    public void setShardThresholdBytes(long shardThresholdBytes) {
        this.shardThresholdBytes = Math.max(1L, shardThresholdBytes);
    }

    /** 界面使用：分片阈值（MB） */
    public int getShardThresholdMb() {
        return (int) (shardThresholdBytes / (1024 * 1024));
    }

    public void setShardThresholdMb(int mb) {
        setShardThresholdBytes(Math.max(1, mb) * 1024L * 1024L);
    }

    public int getMaxShardsPerTable() {
        return maxShardsPerTable;
    }

    public void setMaxShardsPerTable(int maxShardsPerTable) {
        this.maxShardsPerTable = Math.max(1, Math.min(64, maxShardsPerTable));
    }

    public boolean isFastMode() {
        return fastMode;
    }

    public void setFastMode(boolean fastMode) {
        this.fastMode = fastMode;
    }

    /**
     * 单表实际分片数：受并发线程数与单表上限约束；
     * 极速模式使用直接路径插入（Oracle/达梦）时必须是单会话。
     */
    public int effectiveShardCount(boolean singleSessionRequired) {
        if (singleSessionRequired) {
            return 1;
        }
        return Math.max(1, Math.min(threads, maxShardsPerTable));
    }

    public Charset getEncoding() {
        return encoding;
    }

    public void setEncoding(Charset encoding) {
        this.encoding = encoding == null ? StandardCharsets.UTF_8 : encoding;
    }

    public boolean isEmptyAsNull() {
        return emptyAsNull;
    }

    public void setEmptyAsNull(boolean emptyAsNull) {
        this.emptyAsNull = emptyAsNull;
    }

    public ClearMode getClearMode() {
        return clearMode;
    }

    public void setClearMode(ClearMode clearMode) {
        this.clearMode = clearMode;
    }

    public boolean isAutoCreateTable() {
        return autoCreateTable;
    }

    public void setAutoCreateTable(boolean autoCreateTable) {
        this.autoCreateTable = autoCreateTable;
    }

    public boolean isContinueOnError() {
        return continueOnError;
    }

    public void setContinueOnError(boolean continueOnError) {
        this.continueOnError = continueOnError;
    }

    public int getWriteTimeoutSeconds() {
        return writeTimeoutSeconds;
    }

    public void setWriteTimeoutSeconds(int writeTimeoutSeconds) {
        this.writeTimeoutSeconds = Math.max(0, Math.min(3600, writeTimeoutSeconds));
    }
}
