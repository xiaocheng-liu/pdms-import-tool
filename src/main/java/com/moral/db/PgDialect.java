package com.moral.db;

import com.moral.model.ColumnProfile;
import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.model.InferredType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * PostgreSQL 方言：标识符小写、双引号引用、TRUNCATE、时间直接用 setTimestamp 绑定。
 */
public class PgDialect implements Dialect {

    private static final int MAX_VARCHAR = 4000;

    @Override
    public DbType type() {
        return DbType.POSTGRESQL;
    }

    @Override
    public String driverClass() {
        return DbType.POSTGRESQL.getDriverClass();
    }

    @Override
    public String buildUrl(ConnectionConfig cfg) {
        String url = "jdbc:postgresql://" + cfg.getHost() + ":" + cfg.getPort() + "/" + cfg.getDatabase().trim();
        String schema = cfg.getSchema();
        if (schema != null && !schema.trim().isEmpty()) {
            url += "?currentSchema=" + schema.trim().toLowerCase(Locale.ROOT);
        }
        return url;
    }

    @Override
    public String normalizeTableName(String csvName) {
        return csvName == null ? null : csvName.toLowerCase(Locale.ROOT);
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public String fullyQualified(String schema, String table) {
        if (schema == null || schema.trim().isEmpty()) {
            return quote(normalizeTableName(table));
        }
        return quote(schema.trim().toLowerCase(Locale.ROOT)) + "." + quote(normalizeTableName(table));
    }

    @Override
    public String truncateSql(String schema, String table) {
        return "TRUNCATE TABLE " + fullyQualified(schema, table);
    }

    @Override
    public String deleteSql(String schema, String table) {
        return "DELETE FROM " + fullyQualified(schema, table);
    }

    @Override
    public String buildInsertSql(String schema, String table, List<String> columns, List<ColumnProfile> profiles) {
        StringBuilder columnsPart = new StringBuilder();
        StringBuilder valuesPart = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                columnsPart.append(", ");
                valuesPart.append(", ");
            }
            columnsPart.append(quote(normalizeTableName(columns.get(i))));
            valuesPart.append("?");
        }
        return "INSERT INTO " + fullyQualified(schema, table) + " (" + columnsPart + ") VALUES (" + valuesPart + ")";
    }

    @Override
    public String timestampBindExpr(InferredType type) {
        return "?";
    }

    @Override
    public String mapColumnType(ColumnProfile profile) {
        switch (profile.getType()) {
            case LONG:
                return "BIGINT";
            case DOUBLE:
                return "DOUBLE PRECISION";
            case DATE:
                return "DATE";
            case DATETIME:
                return "TIMESTAMP";
            case TEXT:
                return "TEXT";
            default:
                int length = Math.min(MAX_VARCHAR, Math.max(1, (int) Math.ceil(profile.getMaxLength() * 1.5)));
                return "VARCHAR(" + length + ")";
        }
    }

    @Override
    public boolean supportsFastLoad() {
        return true;
    }

    @Override
    public String buildCopyInSql(String schema, String table, List<String> columns,
                                 boolean emptyAsNull, boolean withHeader) {
        StringBuilder columnPart = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                columnPart.append(", ");
            }
            columnPart.append(quote(normalizeTableName(columns.get(i))));
        }
        // NULL '' 表示空字段写入 NULL；否则保留空字符串（默认 \N 表示 NULL）
        String nullToken = emptyAsNull ? "" : "\\N";
        return "COPY " + fullyQualified(schema, table) + " (" + columnPart + ") FROM STDIN WITH (FORMAT csv, HEADER "
                + withHeader + ", NULL '" + nullToken + "')";
    }

    @Override
    public boolean tableExists(Connection conn, String schema, String table) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        String tableName = normalizeTableName(table);
        String[] candidates = schemaCandidates(schema);
        for (String candidate : candidates) {
            try (ResultSet rs = metaData.getTables(null, candidate, tableName, new String[]{"TABLE"})) {
                if (rs != null && rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** PostgreSQL 支持 CREATE SCHEMA IF NOT EXISTS，schema 需与 fullyQualified 一致转小写 */
    @Override
    public String createSchemaIfNotExistsSql(String schema) {
        if (schema == null || schema.trim().isEmpty()) {
            return null;
        }
        return "CREATE SCHEMA IF NOT EXISTS " + quote(schema.trim().toLowerCase(Locale.ROOT));
    }

    private String[] schemaCandidates(String schema) {
        String lower = schema == null || schema.trim().isEmpty() ? null : schema.trim().toLowerCase(Locale.ROOT);
        if (lower == null) {
            return new String[]{"public", null};
        }
        if (lower.equals("public")) {
            return new String[]{"public", null};
        }
        return new String[]{lower, "public", null};
    }
}
