package com.moral.model;

import java.io.File;

/**
 * 一个 CSV 文件对应的一张表。
 */
public class CsvTable {

    private final File file;
    private final String tableName;
    private final long sizeBytes;
    private boolean selected = true;
    private int shardCount = 1;
    private TableProgress progress;

    public CsvTable(File file) {
        this.file = file;
        this.sizeBytes = file.length();
        String name = file.getName();
        if (name.toLowerCase().endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        this.tableName = name;
        this.progress = new TableProgress(this.tableName, this.sizeBytes);
    }

    public File getFile() {
        return file;
    }

    public String getTableName() {
        return tableName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public int getShardCount() {
        return shardCount;
    }

    public void setShardCount(int shardCount) {
        this.shardCount = Math.max(1, shardCount);
        this.progress.setShardCount(this.shardCount);
    }

    public TableProgress getProgress() {
        return progress;
    }

    /** 重新导入时重置进度 */
    public void resetProgress() {
        this.progress = new TableProgress(tableName, sizeBytes);
        this.progress.setShardCount(shardCount);
    }
}
