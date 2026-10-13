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

    /** 允许变长字段跨页存储，避免源库合法的宽记录触发 -2665。 */
    @Override
    public String createTableSuffix() {
        return " STORAGE(USING LONG ROW)";
    }

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
            case DECIMAL:
                return "DECIMAL(38, 10)";
            case DATE:
                return "DATE";
            case DATETIME:
                return "TIMESTAMP";
            case TEXT:
                return "CLOB";
            default:
                // 显式字符语义；按 UTF-8 最多四字节保守控制字段容量。
                int length = Math.max(1, (int) Math.ceil(profile.getMaxLength() * 1.5));
                return length > 2047 ? "CLOB" : "VARCHAR(" + length + " CHAR)";
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
