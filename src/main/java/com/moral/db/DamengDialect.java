package com.moral.db;

import com.moral.model.ColumnProfile;
import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.model.InferredType;

/**
 * 达梦方言：行为与 Oracle 基本一致（大写标识符、双引号、TO_DATE/TO_TIMESTAMP），
 * 但 URL 与驱动独立，便于后续微调。
 */
public class DamengDialect extends OracleDialect {

    @Override
    public DbType type() {
        return DbType.DAMENG;
    }

    @Override
    public String driverClass() {
        return DbType.DAMENG.getDriverClass();
    }

    @Override
    public String buildUrl(ConnectionConfig cfg) {
        String url = "jdbc:dm://" + cfg.getHost() + ":" + cfg.getPort();
        String database = cfg.getDatabase() == null ? "" : cfg.getDatabase().trim();
        if (!database.isEmpty()) {
            url += "/" + database;
        }
        String schema = cfg.getSchema();
        if (schema != null && !schema.trim().isEmpty()) {
            url += "?schema=" + schema.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return url;
    }

    @Override
    public String mapColumnType(ColumnProfile profile) {
        switch (profile.getType()) {
            case LONG:
                return "BIGINT";
            case DOUBLE:
                return "DOUBLE";
            case DATE:
                return "DATE";
            case DATETIME:
                return "TIMESTAMP";
            case TEXT:
                return "CLOB";
            default:
                // 达梦 VARCHAR 按字符计算长度，上限 8188
                int length = Math.min(8188, Math.max(1, (int) Math.ceil(profile.getMaxLength() * 1.5)));
                return "VARCHAR(" + length + ")";
        }
    }

    /** 与 Oracle 一致：时间列用普通占位符 + setTimestamp，避免日期格式/NLS 引发的转换错误 */
    @Override
    public String timestampBindExpr(InferredType type) {
        return "?";
    }

    /** 达梦同样支持 APPEND 直接路径插入 */
    @Override
    public boolean supportsFastLoad() {
        return true;
    }

    @Override
    public boolean fastLoadRequiresSingleSession() {
        return true;
    }

    /** 达梦没有 Oracle 的并行 DML 会话语句，会话语句留空（APPEND 提示不支持时会被自动忽略/降级） */
    @Override
    public java.util.List<String> fastLoadSessionStatements() {
        return java.util.Collections.emptyList();
    }
}
