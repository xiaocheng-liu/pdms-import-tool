package com.moral.model;

/**
 * 单列的采样分析结果（用于建表与参数绑定）。
 */
public class ColumnProfile {

    private final String name;
    private InferredType type = InferredType.STRING;
    private int maxLength = 1;

    public ColumnProfile(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public InferredType getType() {
        return type;
    }

    public void setType(InferredType type) {
        this.type = type;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = Math.max(1, maxLength);
    }
}
