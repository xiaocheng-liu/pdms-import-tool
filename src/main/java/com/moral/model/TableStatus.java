package com.moral.model;

/**
 * 单表导入状态。
 */
public enum TableStatus {
    PENDING("待导入"),
    RUNNING("进行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    CANCELLED("已取消");

    private final String label;

    TableStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
