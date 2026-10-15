package com.moral.model;

/**
 * 单列的采样分析结果（用于建表与参数绑定）。
 */
public class ColumnProfile {

    private final String name;
    private InferredType type = InferredType.STRING;
    private int maxLength = 1;
    private int observedMaxCharacters;
    private String declaredSqlType;

    /** Oracle 脚本转换的建表类型或目标列元数据类型，空值表示仅有采样结果。 */
    public String getDeclaredSqlType() {
        return declaredSqlType;
    }

    /** 同步保存建表类型，参数绑定仍使用 type。 */
    public void setDeclaredSqlType(String declaredSqlType) {
        this.declaredSqlType = declaredSqlType;
    }

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

    /** 实际采样字符数，不包含推断列宽的最小值和扩容余量。 */
    public int getObservedMaxCharacters() {
        return observedMaxCharacters;
    }

    /** 按 Unicode 码点累计实际长度，避免补充字符被当作两个字符。 */
    public void observeCharacters(String value) {
        observedMaxCharacters = Math.max(observedMaxCharacters, value.codePointCount(0, value.length()));
    }
}
