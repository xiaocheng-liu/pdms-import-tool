package com.moral.db;

import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * 人大金仓 KingbaseES 方言（PostgreSQL 兼容模式）。
 *
 * <p>金仓在 SQL 语义上兼容 PostgreSQL，因此复用 {@link PgDialect} 的全部实现：
 * 标识符小写 + 双引号引用、TRUNCATE/DELETE 清表、时间列直接 setTimestamp 绑定、
 * 建表类型映射、{@code CREATE SCHEMA IF NOT EXISTS}、表存在性判断、COPY 语句文本。
 * 本类覆盖驱动、Schema 大小写、目录查询、字符长度语义及 COPY 通道差异。
 */
public class KingbaseDialect extends PgDialect {

    @Override
    public DbType type() {
        return DbType.KINGBASE;
    }

    @Override
    public String driverClass() {
        return DbType.KINGBASE.getDriverClass();
    }

    @Override
    public String buildUrl(ConnectionConfig cfg) {
        String url = "jdbc:kingbase8://" + cfg.getHost() + ":" + cfg.getPort() + "/" + cfg.getDatabase().trim();
        String schema = cfg.getSchema();
        if (schema != null && !schema.trim().isEmpty()) {
            url += "?currentSchema=" + schema.trim();
        }
        return url;
    }

    /** 金仓目录中的 Schema 可能为大写，按实际配置引用。 */
    @Override
    public String fullyQualified(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(normalizeTableName(table))
                : quote(schema.trim()) + "." + quote(normalizeTableName(table));
    }

    /** 未指定 Schema 时限定当前用户 Schema，避免命中系统同名表。 */
    @Override
    public boolean tableExists(Connection conn, String schema, String table) throws SQLException {
        String owner = schema;
        if (owner == null || owner.isBlank()) {
            try (var statement = conn.createStatement(); var rows = statement.executeQuery("SELECT current_schema()")) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("无法确定目标 Schema");
                owner = rows.getString(1);
            }
        }
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_schema=? AND table_name=? "
                + "AND table_type = 'BASE TABLE'";
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, owner.trim());
            statement.setString(2, normalizeTableName(table));
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    /** 采样长度以字符统计，建表必须显式使用字符语义。 */
    @Override
    public String mapColumnType(com.moral.model.ColumnProfile profile) {
        String type = super.mapColumnType(profile);
        return type.startsWith("VARCHAR(") ? type.replace(")", " CHAR)") : type;
    }

    /** 同名表诊断使用金仓目录，不访问 PostgreSQL 目录。 */
    @Override
    public java.util.List<String> findTableInOtherSchemas(Connection conn, String schema, String table) throws SQLException {
        java.util.List<String> found = new java.util.ArrayList<>();
        try (var statement = conn.prepareStatement("SELECT table_schema FROM information_schema.tables "
                + "WHERE table_name=? AND table_schema<>? ORDER BY table_schema")) {
            statement.setString(1, normalizeTableName(table));
            statement.setString(2, schema == null ? "" : schema);
            try (var rows = statement.executeQuery()) { while (rows.next()) found.add(rows.getString(1)); }
        }
        return found;
    }

    /** 创建 Schema 时保留与目标 SQL 一致的大小写。 */
    @Override
    public String createSchemaIfNotExistsSql(String schema) {
        return schema == null || schema.isBlank() ? null : "CREATE SCHEMA IF NOT EXISTS " + quote(schema.trim());
    }

    /** 极速模式：金仓 COPY FROM STDIN，走驱动自带的 CopyManager */
    @Override
    public long copyIn(Connection conn, String copySql, InputStream data,
                       LongConsumer bytesConsumer, BooleanSupplier cancelled)
            throws SQLException, IOException {
        return KingbaseCopyLoader.copyIn(conn, copySql, data, bytesConsumer, cancelled);
    }
}
