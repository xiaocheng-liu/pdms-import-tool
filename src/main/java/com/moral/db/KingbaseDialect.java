package com.moral.db;

import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * 人大金仓 KingbaseES 方言（PostgreSQL 兼容模式）。
 *
 * <p>金仓在 SQL 语义上兼容 PostgreSQL，因此复用 {@link PgDialect} 的全部实现：
 * 标识符小写 + 双引号引用、TRUNCATE/DELETE 清表、时间列直接 setTimestamp 绑定、
 * 建表类型映射、{@code CREATE SCHEMA IF NOT EXISTS}、表存在性判断、COPY 语句文本。
 * 本类只覆盖驱动、JDBC URL 与 COPY 传输通道三处差异。
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
            url += "?currentSchema=" + schema.trim().toLowerCase(Locale.ROOT);
        }
        return url;
    }

    /** 极速模式：金仓 COPY FROM STDIN，走驱动自带的 CopyManager */
    @Override
    public long copyIn(Connection conn, String copySql, InputStream data,
                       LongConsumer bytesConsumer, BooleanSupplier cancelled)
            throws SQLException, IOException {
        return KingbaseCopyLoader.copyIn(conn, copySql, data, bytesConsumer, cancelled);
    }
}
