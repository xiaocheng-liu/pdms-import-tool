package com.moral.db;

import com.moral.model.DbType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从数据库目录读取实际字符列长度，不使用 JDBC 显示宽度推测长度单位。 */
public final class CharacterColumns {
    private CharacterColumns() { }

    /** MySQL definition 保存完整列定义，其余数据库不需要重述列属性。 */
    public record Column(String name, String type, int length, String unit, String definition,
                         String characterSet, String collation) {
        /** 非 MySQL 及不含目录字符属性的定义。 */
        public Column(String name, String type, int length, String unit, String definition) {
            this(name, type, length, unit, definition, "", "");
        }

        /** 失败日志保留实际类型和完整列定义，便于定位属性变化。 */
        public String diagnostic() {
            return display() + (characterSet.isEmpty() ? "" : " CHARACTER SET " + characterSet + " COLLATE " + collation)
                    + (definition.isEmpty() ? "" : "；列定义=" + definition.replace("\n", "\\n").replace("\r", "\\r"));
        }

        /** 供预览和日志显示的实际类型。 */
        public String display() {
            return type + (isCharacter() ? "(" + length + (unit.isEmpty() ? "" : " " + unit) + ")" : "");
        }

        /** 只有可准确识别长度和单位的普通字符列允许扩长。 */
        public boolean isCharacter() {
            return length > 0 && !unit.isEmpty()
                    && List.of("CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2").contains(type);
        }
    }

    /** 实际 Schema 与列名保持数据库返回值，避免写入同名的其他对象。 */
    public record Table(String schema, String name, Map<String, Column> columns) { }

    /** 读取目标表的目录元数据；找不到对象时停止该表，禁止模糊匹配。 */
    public static Table read(Connection connection, Dialect dialect, String schema, String table) throws SQLException {
        String target = dialect.normalizeTableName(table);
        String owner = resolveSchema(connection, dialect, schema, target);
        Map<String, Column> columns = new LinkedHashMap<>();
        boolean oracle = dialect.type() == DbType.ORACLE || dialect.type() == DbType.DAMENG;
        String sql = oracle
                ? "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, CHAR_LENGTH, CHAR_USED FROM ALL_TAB_COLUMNS "
                    + "WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID"
                : "SELECT column_name, data_type, character_maximum_length"
                    + (dialect.type() == DbType.MYSQL ? ", character_set_name, collation_name" : ", domain_name")
                    + " FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        if (dialect.type() == DbType.KINGBASE) {
            sql = "SELECT i.column_name, i.data_type, i.character_maximum_length, i.domain_name, "
                    + "CASE WHEN a.atttypmod < 0 THEN 'BYTE' ELSE 'CHAR' END "
                    + "FROM information_schema.columns i JOIN sys_catalog.sys_namespace n ON n.nspname=i.table_schema "
                    + "JOIN sys_catalog.sys_class c ON c.relnamespace=n.oid AND c.relname=i.table_name "
                    + "JOIN sys_catalog.sys_attribute a ON a.attrelid=c.oid AND a.attname=i.column_name "
                    + "WHERE i.table_schema=? AND i.table_name=? ORDER BY i.ordinal_position";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setString(2, target);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    String type = normalizeType(rows.getString(2));
                    int length;
                    String unit;
                    if (oracle) {
                        String usage = rows.getString(5);
                        unit = type.startsWith("N") ? "CHAR" : "C".equals(usage) ? "CHAR" : "B".equals(usage) ? "BYTE" : "";
                        length = positiveLength(rows, unit.equals("CHAR") ? 4 : 3);
                    } else {
                        length = positiveLength(rows, 3);
                        unit = dialect.type() == DbType.KINGBASE ? rows.getString(5) : "CHAR";
                        if (dialect.type() != DbType.MYSQL && rows.getString(4) != null) {
                            type = "DOMAIN " + rows.getString(4);
                        }
                    }
                    String charset = dialect.type() == DbType.MYSQL ? rows.getString(4) : null;
                    String collation = dialect.type() == DbType.MYSQL ? rows.getString(5) : null;
                    Column column = new Column(name, type, length, unit, "",
                            charset == null ? "" : charset, collation == null ? "" : collation);
                    if (dialect.type() == DbType.MYSQL && column.isCharacter()
                            && (column.characterSet().isEmpty() || column.collation().isEmpty())) {
                        throw new SQLException("无法读取字符集或排序规则：" + owner + "." + target + "." + name);
                    }
                    if (columns.putIfAbsent(name, column) != null) {
                        throw new SQLException("目标元数据存在重复列：" + owner + "." + target + "." + name);
                    }
                }
            }
        }
        if (columns.isEmpty()) throw new SQLException("无法读取目标表字段定义：" + owner + "." + target);
        if (dialect.type() == DbType.MYSQL) {
            String createSql;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SHOW CREATE TABLE " + qualified(dialect, owner, target))) {
                if (!rows.next()) throw new SQLException("无法读取 MySQL 完整建表定义：" + target);
                createSql = rows.getString(2);
            }
            Map<String, String> definitions = mysqlDefinitions(createSql, DatabaseComments.noBackslashEscapes(connection));
            columns.replaceAll((name, column) -> new Column(name, column.type(), column.length(), column.unit(),
                    definitions.getOrDefault(name, ""), column.characterSet(), column.collation()));
        }
        return new Table(owner, target, columns);
    }

    /** 读取真实容量；无限长度、大字段和缺失容量均不参与字符扩长。 */
    private static int positiveLength(ResultSet rows, int index) throws SQLException {
        long value = rows.getLong(index);
        return rows.wasNull() || value <= 0 || value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    /** 统一目录中的类型别名，仅合并同义类型，不合并定长与变长。 */
    public static String normalizeType(String type) {
        if (type == null) return "";
        return switch (type.trim().toUpperCase(Locale.ROOT)) {
            case "CHARACTER VARYING" -> "VARCHAR";
            case "CHARACTER", "BPCHAR" -> "CHAR";
            default -> type.trim().toUpperCase(Locale.ROOT);
        };
    }

    /** 空 Schema 时定位实际写入对象；MySQL 始终采用连接的 catalog。 */
    private static String resolveSchema(Connection conn, Dialect dialect, String schema, String table) throws SQLException {
        if (dialect.type() == DbType.MYSQL) {
            String catalog = conn.getCatalog();
            if (catalog == null || catalog.isBlank()) throw new SQLException("无法确定 MySQL 当前数据库");
            return catalog;
        }
        if (schema != null && !schema.isBlank()) {
            return dialect.type() == DbType.KINGBASE ? schema.trim() : dialect.normalizeTableName(schema.trim());
        }
        if (dialect.type() == DbType.KINGBASE) {
            // 保留金仓返回的真实大小写，不能把 PUBLIC 改成 public。
            try (Statement statement = conn.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT current_schema()")) {
                if (rows.next() && rows.getString(1) != null) return rows.getString(1);
            }
            throw new SQLException("无法确定目标 Schema，请显式指定");
        }
        if (dialect.type() == DbType.POSTGRESQL) {
            String sql = "SELECT n.nspname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                    + "WHERE c.oid=pg_catalog.to_regclass(?::text)";
            try (PreparedStatement statement = conn.prepareStatement(sql)) {
                statement.setString(1, dialect.quote(table));
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) return rows.getString(1);
                }
            }
            throw new SQLException("无法确定目标表实际 Schema：" + table);
        }
        String owner;
        try {
            owner = conn.getSchema();
        } catch (SQLException | AbstractMethodError unsupported) {
            owner = null;
        }
        if (owner == null || owner.isBlank()) owner = conn.getMetaData().getUserName();
        if (owner == null || owner.isBlank()) throw new SQLException("无法确定目标 Schema");
        return dialect.normalizeTableName(owner.trim());
    }

    /** 扩长始终使用完整限定名，MySQL 也显式限定当前 catalog。 */
    public static String qualified(Dialect dialect, String schema, String table) {
        return identifier(dialect, schema) + "." + identifier(dialect, table);
    }

    /** 对目录读取的标识符转义，不更改大小写。 */
    public static String identifier(Dialect dialect, String name) {
        String quote = dialect.type() == DbType.MYSQL ? "`" : "\"";
        return quote + name.replace(quote, quote + quote) + quote;
    }

    /** 生成单列扩长语句；MySQL 只替换原定义中的长度。 */
    public static String widenSql(Dialect dialect, String schema, String table, Column column, int length) {
        String target = qualified(dialect, schema, table);
        String declaration = column.type() + "(" + length
                + ((dialect.type() == DbType.ORACLE || dialect.type() == DbType.DAMENG || dialect.type() == DbType.KINGBASE)
                    && !column.type().startsWith("N") ? " " + column.unit() : "") + ")";
        return switch (dialect.type()) {
            case MYSQL -> {
                String full = mysqlDefinition(column, length);
                yield full == null ? null : "ALTER TABLE " + target + " MODIFY COLUMN " + full;
            }
            case POSTGRESQL, KINGBASE -> "ALTER TABLE " + target + " ALTER COLUMN "
                    + identifier(dialect, column.name()) + " TYPE " + declaration;
            case ORACLE, DAMENG -> "ALTER TABLE " + target + " MODIFY ("
                    + identifier(dialect, column.name()) + " " + declaration + ")";
        };
    }

    /** 除长度外，预览中的类型、单位及 MySQL 列属性必须保持一致。 */
    public static boolean sameDefinition(Column before, Column current) {
        if (!before.name().equals(current.name()) || !before.type().equals(current.type())
                || !before.unit().equals(current.unit())) return false;
        if (!before.characterSet().equalsIgnoreCase(current.characterSet())
                || !before.collation().equalsIgnoreCase(current.collation())) return false;
        if (before.definition().isEmpty() && current.definition().isEmpty()) return true;
        String old = mysqlDefinition(before, 1);
        String now = mysqlDefinition(current, 1);
        return old != null && old.equals(now);
    }

    /** 用目录中的有效字符属性补全 SHOW CREATE 的省略项，其余属性保持原文。 */
    public static String mysqlDefinition(Column column, int length) {
        String full = mysqlWithLength(column.definition(), column.name(), column.type(), column.length(), length);
        if (full == null || (column.characterSet().isEmpty() && column.collation().isEmpty())) return full;
        if (!column.characterSet().matches("[A-Za-z0-9_]+") || !column.collation().matches("[A-Za-z0-9_]+")) return null;
        Matcher head = Pattern.compile("(?is)^`(?:``|[^`])+`\\s+(?:CHAR|VARCHAR)\\s*\\(\\s*\\d+\\s*\\)").matcher(full);
        if (!head.find()) return null;
        int end = head.end();
        String tail = full.substring(end);
        boolean charsetSeen = false, collationSeen = false;
        while (true) {
            Matcher attribute = Pattern.compile("(?i)^\\s+(CHARACTER\\s+SET|COLLATE)\\s+([A-Za-z0-9_]+)\\b").matcher(tail);
            if (!attribute.find()) break;
            boolean charset = !attribute.group(1).equalsIgnoreCase("COLLATE");
            String expected = charset ? column.characterSet() : column.collation();
            if (!attribute.group(2).equalsIgnoreCase(expected) || (charset ? charsetSeen : collationSeen)) return null;
            if (charset) charsetSeen = true;
            else collationSeen = true;
            tail = tail.substring(attribute.end());
        }
        // 不认识的字符属性不能与补全项一起写回，避免生成重复或改变语义的声明。
        if (tail.stripLeading().matches("(?is)^(CHARACTER|COLLATE)\\b.*")) return null;
        return full.substring(0, end) + " CHARACTER SET " + column.characterSet() + " COLLATE " + column.collation()
                + (tail.isBlank() ? "" : " " + tail.stripLeading());
    }

    /** 严格确认列名、类型和旧长度后，仅替换长度数字，完整保留其他属性。 */
    private static String mysqlWithLength(String definition, String name, String type, int oldLength, int length) {
        Matcher matcher = Pattern.compile("(?is)^(`(?:``|[^`])+`)\\s+(CHAR|VARCHAR)\\s*\\(\\s*(\\d+)\\s*\\)")
                .matcher(definition);
        if (!matcher.find() || !matcher.group(1).equals("`" + name.replace("`", "``") + "`")
                || !matcher.group(2).equalsIgnoreCase(type)
                || !matcher.group(3).equals(String.valueOf(oldLength))) return null;
        return definition.substring(0, matcher.start(3)) + length + definition.substring(matcher.end(3));
    }

    /** 按顶层逗号拆分 SHOW CREATE TABLE，字符串、引用名和表达式中的逗号保持原样。 */
    static Map<String, String> mysqlDefinitions(String ddl) {
        return mysqlDefinitions(ddl, false);
    }

    /** NO_BACKSLASH_ESCAPES 模式下反斜杠不转义后续引号。 */
    static Map<String, String> mysqlDefinitions(String ddl, boolean noBackslashEscapes) {
        Map<String, String> definitions = new LinkedHashMap<>();
        if (ddl == null) return definitions;
        int start = ddl.indexOf('(');
        if (start < 0) return definitions;
        int depth = 1;
        char quote = 0;
        List<String> parts = new ArrayList<>();
        int part = start + 1;
        for (int i = part; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote != '`' && !noBackslashEscapes) { i++; continue; }
                if (c == quote) {
                    if (i + 1 < ddl.length() && ddl.charAt(i + 1) == quote) i++;
                    else quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') depth++;
            else if (c == ')') {
                if (--depth == 0) { parts.add(ddl.substring(part, i).trim()); break; }
            } else if (c == ',' && depth == 1) {
                parts.add(ddl.substring(part, i).trim());
                part = i + 1;
            }
        }
        if (depth != 0 || quote != 0) return definitions;
        for (String definition : parts) {
            Matcher matcher = Pattern.compile("^`((?:``|[^`])+)`\\s+", Pattern.DOTALL).matcher(definition);
            if (matcher.find() && definitions.putIfAbsent(matcher.group(1).replace("``", "`"), definition) != null) {
                return Map.of();
            }
        }
        return definitions;
    }
}
