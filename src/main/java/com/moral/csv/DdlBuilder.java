package com.moral.csv;

import com.moral.db.Dialect;
import com.moral.model.ColumnProfile;

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
                    .append(dialect.mapColumnType(profile));
        }
        sql.append("\n)");
        return sql.toString();
    }
}
