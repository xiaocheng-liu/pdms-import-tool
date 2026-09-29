package com.moral.model;

/**
 * 由 CSV 采样数据推断出的列类型。
 */
public enum InferredType {
    /** 整数 */
    LONG,
    /** 小数 */
    DOUBLE,
    /** 日期 yyyy-MM-dd */
    DATE,
    /** 日期时间 yyyy-MM-dd HH:mm:ss */
    DATETIME,
    /** 超长文本（超过数据库 VARCHAR 上限） */
    TEXT,
    /** 普通字符串 */
    STRING
}
