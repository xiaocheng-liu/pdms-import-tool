package com.moral.model;

import com.moral.db.CharacterColumns.Column;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次预检的结构变更计划；建表可编辑，字段变更语句与比较基线不可编辑。 */
public final class SchemaPlan {
    private final Map<String, Table> tables = new LinkedHashMap<>();

    /** 按 CSV 文件名保存逐表结果，包括检查失败的表。 */
    public Map<String, Table> tables() { return tables; }

    /** 是否存在需要用户确认的结构变更。 */
    public boolean hasChanges() {
        return tables.values().stream().anyMatch(t -> t.error().isEmpty()
                && (t.createSql() != null || !t.widenings().isEmpty() || !t.additions().isEmpty() || !t.comments().isEmpty()));
    }

    /** 失败表数量，不能将失败误报为无需变更。 */
    public long failedCount() { return tables.values().stream().filter(t -> !t.error().isEmpty()).count(); }

    /** 兼容旧建表入口；此入口不产生字段变更授权。 */
    public static SchemaPlan fromCreateSql(Map<String, String> ddl) {
        SchemaPlan plan = new SchemaPlan();
        if (ddl != null) ddl.forEach((name, sql) -> {
            Table table = new Table(name);
            table.setCreateSql(sql);
            plan.tables.put(name, table);
        });
        return plan;
    }

    /** 单列扩长：保留实际 Schema、旧定义、目标长度和预览语句。 */
    public record Widening(String schema, String table, Column before, int length, String sql) { }

    /** 单列新增：仅保存已转换类型，新增列允许 NULL，不复制默认值与约束。 */
    public record Addition(String schema, String table, String column, String declaration, String sql) { }

    /** sql 为 null 时，注释已包含在建表或新增字段语句内，只验证结果。 */
    public record Comment(String schema, String table, String column, String before, String after,
                          String sql, Column baseline, boolean newTarget) { }

    /** 单表预检结果；错误表即使含有部分操作也禁止执行。 */
    public static final class Table {
        private final String name;
        private String createSql;
        private String error = "";
        private final List<String> notes = new ArrayList<>();
        private final List<Widening> widenings = new ArrayList<>();
        private final List<Addition> additions = new ArrayList<>();
        private final List<Comment> comments = new ArrayList<>();
        private Boolean mysqlNoBackslashEscapes;

        public Table(String name) { this.name = name; }
        public String name() { return name; }
        public String createSql() { return createSql; }
        public void setCreateSql(String sql) { createSql = sql; }
        public String error() { return error; }
        public void setError(String error) { this.error = error; }
        public List<String> notes() { return notes; }
        public List<Widening> widenings() { return widenings; }
        public List<Addition> additions() { return additions; }
        public List<Comment> comments() { return comments; }
        public Boolean mysqlNoBackslashEscapes() { return mysqlNoBackslashEscapes; }
        public void setMysqlNoBackslashEscapes(Boolean value) { mysqlNoBackslashEscapes = value; }
    }
}
