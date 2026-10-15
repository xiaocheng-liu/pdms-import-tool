package com.moral.task;

import com.moral.csv.CsvMeta;
import com.moral.csv.DdlBuilder;
import com.moral.csv.OracleTableDefinitions;
import com.moral.db.CharacterColumns;
import com.moral.db.DatabaseComments;
import com.moral.db.Dialect;
import com.moral.model.DbType;
import com.moral.model.SchemaPlan;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 源注释的差异预览、会话复核和执行后验证。 */
public final class SchemaComments {
    private SchemaComments() { }

    /** 仅同步表注释和 CSV 使用列；未声明的注释不清空。 */
    public static void prepare(Connection connection, Dialect dialect, String schema, String table, CsvMeta meta,
                               boolean exists, OracleTableDefinitions.Comments source, SchemaPlan.Table plan) throws SQLException {
        if (source.isEmpty()) return;
        CharacterColumns.Table target = exists ? CharacterColumns.read(connection, dialect, schema, table) : null;
        String owner = target == null ? newTableSchema(connection, dialect, schema) : target.schema();
        String name = target == null ? dialect.normalizeTableName(table) : target.name();
        Map<String, String> current = exists ? DatabaseComments.read(connection, dialect, owner, name) : Map.of();
        boolean mysql = dialect.type() == DbType.MYSQL;
        boolean noBackslash = mysql && DatabaseComments.noBackslashEscapes(connection);
        if (mysql) plan.setMysqlNoBackslashEscapes(noBackslash);
        Map<String, String> desired = new LinkedHashMap<>();
        if (source.table() != null) desired.put("", source.table());
        for (String column : meta.getColumns()) {
            String comment = source.columns().get(column.toUpperCase(java.util.Locale.ROOT));
            if (comment != null) desired.put(dialect.normalizeTableName(column), comment);
        }
        Map<String, String> createColumnComments = new LinkedHashMap<>();
        String createTableComment = null;
        for (var entry : desired.entrySet()) {
            String column = entry.getKey(), after = entry.getValue();
            boolean added = plan.additions().stream().anyMatch(change -> change.column().equals(column));
            boolean newTarget = !exists || added;
            if (exists && !column.isEmpty() && !current.containsKey(column) && !added) {
                throw new SQLException("无法同步注释，目标缺少字段：" + name + "." + column);
            }
            String before = current.getOrDefault(column, "");
            if (before.equals(after)) continue;
            String literal = DatabaseComments.literal(dialect, after, noBackslash);
            CharacterColumns.Column baseline = null;
            String sql;
            if (mysql && !exists) {
                if (column.isEmpty()) createTableComment = literal;
                else createColumnComments.put(column, literal);
                sql = null;
            } else if (mysql && added) {
                for (int i = 0; i < plan.additions().size(); i++) {
                    SchemaPlan.Addition change = plan.additions().get(i);
                    if (change.column().equals(column)) plan.additions().set(i, new SchemaPlan.Addition(change.schema(),
                            change.table(), column, change.declaration(), change.sql() + " COMMENT " + literal));
                }
                sql = null;
            } else if (mysql && !column.isEmpty()) {
                baseline = target.columns().get(column);
                if (baseline == null || baseline.definition().isEmpty()) throw new SQLException("无法读取完整字段定义：" + column);
                int length = baseline.length();
                for (SchemaPlan.Widening change : plan.widenings()) {
                    if (change.before().name().equals(column)) length = change.length();
                }
                String definition = baseline.isCharacter() ? CharacterColumns.mysqlDefinition(baseline, length) : baseline.definition();
                if (definition == null) throw new SQLException("无法可靠保留字段属性：" + column);
                baseline = new CharacterColumns.Column(column, baseline.type(), length, baseline.unit(), definition,
                        baseline.characterSet(), baseline.collation());
                sql = "ALTER TABLE " + CharacterColumns.qualified(dialect, owner, name) + " MODIFY COLUMN "
                        + DatabaseComments.mysqlColumn(definition, literal, noBackslash);
            } else sql = DatabaseComments.sql(dialect, owner, name, column, literal);
            plan.comments().add(new SchemaPlan.Comment(owner, name, column, before, after, sql, baseline, newTarget));
        }
        if (mysql && !exists && (!createColumnComments.isEmpty() || createTableComment != null)) {
            plan.setCreateSql(DdlBuilder.build(dialect, schema, table, meta, createColumnComments, createTableComment));
        }
    }

    /** 新表尚无目录记录，使用与建表一致的显式 Schema 或会话默认 Schema。 */
    private static String newTableSchema(Connection connection, Dialect dialect, String schema) throws SQLException {
        String owner = dialect.type() == DbType.MYSQL ? connection.getCatalog() : schema;
        if (owner == null || owner.isBlank()) owner = connection.getSchema();
        if ((owner == null || owner.isBlank()) && (dialect.type() == DbType.ORACLE || dialect.type() == DbType.DAMENG)) {
            owner = connection.getMetaData().getUserName();
        }
        if (owner == null || owner.isBlank()) throw new SQLException("无法确定注释目标 Schema，请显式指定");
        return dialect.type() == DbType.MYSQL ? owner : dialect.normalizeTableName(owner);
    }

    /** 防止预览与执行连接采用不同的 MySQL 字符串转义规则。 */
    public static void validateSession(Connection connection, SchemaPlan.Table plan) throws SQLException {
        if (plan != null && plan.mysqlNoBackslashEscapes() != null
                && plan.mysqlNoBackslashEscapes() != DatabaseComments.noBackslashEscapes(connection)) {
            throw new SQLException("MySQL sql_mode 已变化，请重新预览注释 SQL");
        }
    }

    /** 在结构变更前检查已有注释，避免执行预览之外的覆盖。 */
    public static void precheck(Connection connection, Dialect dialect, SchemaPlan.Table plan) throws SQLException {
        validateSession(connection, plan);
        if (!plan.comments().isEmpty()) {
            SchemaPlan.Comment first = plan.comments().getFirst();
            CharacterColumns.Table actual = CharacterColumns.read(connection, dialect, first.schema(), first.table());
            if (!first.schema().equals(actual.schema()) || !first.table().equals(actual.name())) {
                throw new SQLException("注释目标数据库或 Schema 已变化，请重新预览");
            }
        }
        for (SchemaPlan.Comment change : plan.comments()) {
            if (change.newTarget()) continue;
            String actual = DatabaseComments.read(connection, dialect, change.schema(), change.table()).get(change.column());
            if (!change.before().equals(actual) && !change.after().equals(actual)) {
                throw new SQLException("目标注释已变化，请重新预览：" + change.table() + "." + change.column());
            }
            if (change.baseline() != null && !change.after().equals(actual)) {
                CharacterColumns.Column baseline = change.baseline();
                for (SchemaPlan.Widening widening : plan.widenings()) {
                    if (widening.before().name().equals(change.column())) baseline = widening.before();
                }
                validateColumn(connection, dialect, change, baseline);
            }
        }
    }

    /** 逐条提交并验证注释，任何异常均由调用方在清表前停止该表。 */
    public static void apply(Connection connection, Dialect dialect, SchemaPlan.Table plan,
                              Consumer<String> logger, BooleanSupplier cancelled) throws SQLException {
        for (SchemaPlan.Comment change : plan.comments()) {
            if (cancelled.getAsBoolean()) throw new SQLException("导入已取消", "57014");
            String actual = DatabaseComments.read(connection, dialect, change.schema(), change.table()).get(change.column());
            if (change.after().equals(actual)) continue;
            if (change.sql() == null) throw new SQLException("建表或新增字段后的注释验证失败：" + change.table() + "." + change.column());
            if (!change.before().equals(actual)) throw new SQLException("目标注释已变化，请重新预览：" + change.table() + "." + change.column());
            if (change.baseline() != null) validateColumn(connection, dialect, change, change.baseline());
            logger.accept("[执行注释] " + change.sql());
            SchemaPreparation.executeDdl(connection, change.sql());
            String verified = DatabaseComments.read(connection, dialect, change.schema(), change.table()).get(change.column());
            if (!change.after().equals(verified)) throw new SQLException("注释执行后验证失败：" + change.table() + "." + change.column());
            logger.accept("[已同步注释] " + change.table() + (change.column().isEmpty() ? "（表）" : "." + change.column()));
        }
    }
    /** MySQL MODIFY 前核对完整列属性，字符列允许 SHOW CREATE 的等价省略写法。 */
    private static void validateColumn(Connection connection, Dialect dialect, SchemaPlan.Comment change,
                                       CharacterColumns.Column expected) throws SQLException {
        CharacterColumns.Column column = CharacterColumns.read(connection, dialect, change.schema(), change.table())
                .columns().get(change.column());
        boolean matches = column != null && expected.type().equals(column.type()) && expected.length() == column.length()
                && (expected.isCharacter() ? CharacterColumns.sameDefinition(expected, column)
                : expected.definition().equals(column.definition()));
        if (!matches) throw new SQLException("同步注释前列属性已变化：" + change.table() + "." + change.column());
    }

}
