package com.moral.csv;

import com.moral.db.Dialect;
import com.moral.model.ColumnProfile;
import com.moral.model.DbType;

import java.util.ArrayList;
import java.util.List;

/**
 * 按方言生成 CREATE TABLE 语句。
 */
public final class DdlBuilder {

    private DdlBuilder() {
    }

    public static String build(Dialect dialect, String schema, String table, CsvMeta meta) {
        if (meta == null || !meta.isValid()) {
            throw new IllegalArgumentException("CSV 表头为空，无法生成建表语句");
        }
        List<String> types = columnTypes(dialect, meta);
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ").append(dialect.fullyQualified(schema, table)).append(" (\n");
        for (int i = 0; i < meta.getColumns().size(); i++) {
            ColumnProfile profile = meta.getProfiles().get(i);
            if (i > 0) {
                sql.append(",\n");
            }
            sql.append("  ")
                    .append(dialect.quote(dialect.normalizeTableName(profile.getName())))
                    .append(" ")
                    .append(types.get(i));
        }
        sql.append("\n)").append(dialect.createTableSuffix());
        return sql.toString();
    }
    /** MySQL 按 utf8mb4 估算行宽，宽表优先把最长的 VARCHAR 转为 LONGTEXT。 */
    private static List<String> columnTypes(Dialect dialect, CsvMeta meta) {
        List<String> types = new ArrayList<>();
        for (ColumnProfile profile : meta.getProfiles()) {
            types.add(profile.getDeclaredSqlType() == null ? dialect.mapColumnType(profile) : profile.getDeclaredSqlType());
        }
        if (dialect.type() != DbType.MYSQL
                || meta.getProfiles().stream().noneMatch(p -> p.getDeclaredSqlType() != null)) return types;
        int[] widths = new int[types.size()];
        long width = types.size() * 32L;
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).startsWith("VARCHAR(")) {
                widths[i] = Integer.parseInt(types.get(i).substring(8, types.get(i).length() - 1)) * 4;
                width += widths[i];
            }
        }
        while (width > 60000) {
            int longest = -1;
            for (int i = 0; i < widths.length; i++) {
                if (widths[i] > 0 && (longest < 0 || widths[i] > widths[longest])) longest = i;
            }
            if (longest < 0) break;
            types.set(longest, "LONGTEXT");
            width -= widths[longest];
            widths[longest] = 0;
        }
        return types;
    }
}
