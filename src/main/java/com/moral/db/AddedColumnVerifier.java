package com.moral.db;

import com.moral.model.DbType;
import com.moral.model.SchemaPlan;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 新增字段后核对目录类型、字符容量及数值精度，验证成功后才允许清表。 */
public final class AddedColumnVerifier {
    private AddedColumnVerifier() { }

    /** 查询限定目标，检查类型和允许 NULL；字符长度以数据库目录为准。 */
    public static void verify(Connection connection, Dialect dialect, SchemaPlan.Addition change) throws SQLException {
        CharacterColumns.Table table = CharacterColumns.read(connection, dialect, change.schema(), change.table());
        CharacterColumns.Column column = table.columns().get(change.column());
        String declaration = change.declaration();
        String expectedType = declaration.replaceAll("\\(.*\\)", "");
        if (column == null || !canonicalType(expectedType, dialect.type())
                .equals(canonicalType(column.type().replaceAll("\\(.*\\)", ""), dialect.type()))) {
            throw new SQLException("新增字段类型验证失败：" + change.column() + "，预期 " + declaration);
        }
        Matcher chars = Pattern.compile("(?:VARCHAR2|VARCHAR|NVARCHAR2|CHAR|NCHAR)\\((\\d+)(?: (BYTE|CHAR))?\\)")
                .matcher(declaration);
        if (chars.matches()) {
            String unit = chars.group(2) == null ? "CHAR" : chars.group(2);
            if (column.length() != Integer.parseInt(chars.group(1)) || !unit.equals(column.unit())) {
                throw new SQLException("新增字段长度或单位验证失败：" + change.column());
            }
        }
        String sql = "SELECT " + CharacterColumns.identifier(dialect, change.column()) + " FROM "
                + CharacterColumns.qualified(dialect, change.schema(), change.table()) + " WHERE 1=0";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata.getColumnCount() != 1 || metadata.isNullable(1) != ResultSetMetaData.columnNullable) {
                throw new SQLException("新增字段可空性验证失败：" + change.column());
            }
            Matcher number = Pattern.compile("(?:NUMBER|NUMERIC|DECIMAL)\\((\\d+),(-?\\d+)\\)").matcher(declaration);
            if (number.matches() && (metadata.getPrecision(1) != Integer.parseInt(number.group(1))
                    || metadata.getScale(1) != Integer.parseInt(number.group(2)))) {
                throw new SQLException("新增字段数值精度验证失败：" + change.column());
            }
        }
    }

    /** 目录同义词归一化，不把不同字符类型视为相同类型。 */
    private static String canonicalType(String type, DbType database) {
        String normalized = CharacterColumns.normalizeType(type);
        if (database == DbType.ORACLE || database == DbType.DAMENG) {
            return switch (normalized) {
                case "VARCHAR" -> database == DbType.ORACLE ? "VARCHAR2" : "VARCHAR";
                case "DECIMAL", "NUMERIC" -> "NUMBER";
                case "REAL", "DOUBLE PRECISION" -> "FLOAT";
                default -> normalized;
            };
        }
        return switch (normalized) {
            case "TIMESTAMP WITHOUT TIME ZONE" -> "TIMESTAMP";
            case "DECIMAL" -> "NUMERIC";
            default -> normalized;
        };
    }
}
