package com.moral.db;

import com.moral.model.DbType;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** 读取数据库实际注释并生成方言语句，不执行源脚本。 */
public final class DatabaseComments {
    private DatabaseComments() { }

    /** 空列名对应表注释，其余键为数据库实际列名。 */
    public static Map<String, String> read(Connection connection, Dialect dialect, String schema, String table) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        String tableQuery;
        String columnQuery;
        switch (dialect.type()) {
            case ORACLE, DAMENG -> {
                tableQuery = "SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER=? AND TABLE_NAME=?";
                columnQuery = "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER=? AND TABLE_NAME=?";
            }
            case MYSQL -> {
                tableQuery = "SELECT TABLE_COMMENT FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
                columnQuery = "SELECT COLUMN_NAME, COLUMN_COMMENT FROM information_schema.columns WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
            }
            default -> {
                tableQuery = "SELECT obj_description(c.oid,'pg_class') FROM pg_catalog.pg_class c "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?";
                columnQuery = "SELECT a.attname, col_description(c.oid,a.attnum) FROM pg_catalog.pg_class c "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid "
                        + "WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped";
            }
        }
        if (dialect.type() == DbType.KINGBASE) {
            tableQuery = tableQuery.replace("pg_catalog.pg_", "sys_catalog.sys_")
                    .replace("'pg_class'", "'sys_class'");
            columnQuery = columnQuery.replace("pg_catalog.pg_", "sys_catalog.sys_");
        }
        try (PreparedStatement statement = connection.prepareStatement(tableQuery)) {
            statement.setString(1, schema); statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("无法读取表注释：" + schema + "." + table);
                result.put("", empty(rows.getString(1)));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(columnQuery)) {
            statement.setString(1, schema); statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), empty(rows.getString(2)));
            }
        }
        return result;
    }

    /** MySQL 字符串转义取决于当前会话模式，预览和执行必须一致。 */
    public static boolean noBackslashEscapes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
            if (!rows.next()) throw new SQLException("无法读取 MySQL sql_mode");
            return java.util.Arrays.stream(empty(rows.getString(1)).split(","))
                    .anyMatch(mode -> mode.trim().equalsIgnoreCase("NO_BACKSLASH_ESCAPES"));
        }
    }

    /** 转义注释正文，保留换行、反斜杠与单引号。 */
    public static String literal(Dialect dialect, String text, boolean noBackslashEscapes) throws SQLException {
        if (text.indexOf('\0') >= 0) throw new SQLException("注释包含不支持的 NUL 字符");
        boolean pg = dialect.type() == DbType.POSTGRESQL || dialect.type() == DbType.KINGBASE;
        String escaped = (pg || (dialect.type() == DbType.MYSQL && !noBackslashEscapes))
                ? text.replace("\\", "\\\\") : text;
        return (pg ? "E" : "") + "'" + escaped.replace("'", "''") + "'";
    }

    /** 非 MySQL 使用 COMMENT ON，MySQL 表注释使用 ALTER TABLE。 */
    public static String sql(Dialect dialect, String schema, String table, String column, String literal) {
        String target = CharacterColumns.qualified(dialect, schema, table);
        if (dialect.type() == DbType.MYSQL) {
            if (!column.isEmpty()) throw new IllegalArgumentException("MySQL 字段注释必须保留完整列定义");
            return "ALTER TABLE " + target + " COMMENT = " + literal;
        }
        return "COMMENT ON " + (column.isEmpty() ? "TABLE " + target
                : "COLUMN " + target + "." + CharacterColumns.identifier(dialect, column)) + " IS " + literal;
    }

    /** 仅替换顶层 COMMENT 的字符串，默认值、表达式和其他列属性原样保留。 */
    public static String mysqlColumn(String definition, String literal, boolean noBackslashEscapes) throws SQLException {
        int depth = 0;
        int commentStart = -1, commentEnd = -1;
        for (int i = 0; i < definition.length();) {
            char ch = definition.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`') { i = quotedEnd(definition, i, noBackslashEscapes); continue; }
            if (definition.startsWith("/*", i)) {
                int end = definition.indexOf("*/", i + 2);
                if (end < 0) throw new SQLException("MySQL 列定义注释未闭合");
                i = end + 2; continue;
            }
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (depth < 0) throw new SQLException("MySQL 列定义括号无效");
            if (Character.isLetter(ch) || ch == '_') {
                int start = i++;
                while (i < definition.length() && (Character.isLetterOrDigit(definition.charAt(i)) || definition.charAt(i) == '_')) i++;
                if (depth == 0 && definition.substring(start, i).equalsIgnoreCase("COMMENT")) {
                    while (i < definition.length() && Character.isWhitespace(definition.charAt(i))) i++;
                    if (commentStart >= 0 || i >= definition.length() || definition.charAt(i) != '\'') {
                        throw new SQLException("无法可靠解析 MySQL 列注释");
                    }
                    commentStart = i; commentEnd = quotedEnd(definition, i, noBackslashEscapes); i = commentEnd;
                }
            } else i++;
        }
        if (depth != 0) throw new SQLException("MySQL 列定义括号未闭合");
        return commentStart < 0 ? definition + " COMMENT " + literal
                : definition.substring(0, commentStart) + literal + definition.substring(commentEnd);
    }

    /** 跳过字符串或引用标识符，避免把其内容识别成列属性。 */
    private static int quotedEnd(String text, int start, boolean noBackslashEscapes) throws SQLException {
        char quote = text.charAt(start);
        for (int i = start + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && quote != '`' && !noBackslashEscapes) { i++; continue; }
            if (ch == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) { i++; continue; }
                return i + 1;
            }
        }
        throw new SQLException("MySQL 列定义字符串未闭合");
    }

    /** 数据库无注释统一为空字符串。 */
    private static String empty(String value) { return value == null ? "" : value; }
}
