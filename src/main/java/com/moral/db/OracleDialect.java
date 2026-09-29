package com.moral.db;

import com.moral.model.ColumnProfile;
import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.model.InferredType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Oracle 方言：标识符大写、双引号引用、TRUNCATE/DELETE、TO_DATE/TO_TIMESTAMP 绑定。
 */
public class OracleDialect implements Dialect {

    /** Oracle VARCHAR2 最大长度（按字符） */
    private static final int MAX_VARCHAR = 4000;

    @Override
    public DbType type() {
        return DbType.ORACLE;
    }

    @Override
    public String driverClass() {
        return DbType.ORACLE.getDriverClass();
    }

    @Override
    public String buildUrl(ConnectionConfig cfg) {
        String database = cfg.getDatabase().trim();
        // 支持 "SID:xxx" 写法，默认按服务名处理
        if (database.toUpperCase(Locale.ROOT).startsWith("SID:")) {
            return "jdbc:oracle:thin:@" + cfg.getHost() + ":" + cfg.getPort() + ":" + database.substring(4);
        }
        return "jdbc:oracle:thin:@//" + cfg.getHost() + ":" + cfg.getPort() + "/" + database;
    }

    @Override
    public String normalizeTableName(String csvName) {
        return csvName == null ? null : csvName.toUpperCase(Locale.ROOT);
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
        return quote(schema.trim().toUpperCase(Locale.ROOT)) + "." + quote(normalizeTableName(table));
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
            ColumnProfile profile = profiles == null || profiles.size() <= i ? null : profiles.get(i);
            valuesPart.append(bindExpr(profile));
        }
        return "INSERT INTO " + fullyQualified(schema, table) + " (" + columnsPart + ") VALUES (" + valuesPart + ")";
    }

    private String bindExpr(ColumnProfile profile) {
        if (profile == null) {
            return "?";
        }
        if (profile.getType() == InferredType.DATE || profile.getType() == InferredType.DATETIME) {
            return timestampBindExpr(profile.getType());
        }
        return "?";
    }

    /**
     * 时间列统一使用普通占位符：由 JDBC 的 setTimestamp 直接绑定 TIMESTAMP 值。
     * 早期版本在这里套了 TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS')，会把 TIMESTAMP
     * 绑定值先按会话的 NLS_TIMESTAMP_FORMAT 隐式转成字符串（可能变成 28-SEP-26 形式），
     * 再按给定格式解析，导致 ORA-01858（在要求输入数字处找到非数字字符）。
     */
    @Override
    public String timestampBindExpr(InferredType type) {
        return "?";
    }

    @Override
    public String mapColumnType(ColumnProfile profile) {
        switch (profile.getType()) {
            case LONG:
                return "NUMBER(19)";
            case DOUBLE:
                return "NUMBER(38, 8)";
            case DATE:
                return "DATE";
            case DATETIME:
                return "TIMESTAMP";
            case TEXT:
                return "CLOB";
            default:
                int length = Math.min(MAX_VARCHAR, Math.max(1, (int) Math.ceil(profile.getMaxLength() * 1.5)));
                return "VARCHAR2(" + length + " CHAR)";
        }
    }

    @Override
    public boolean supportsFastLoad() {
        return true;
    }

    /** 直接路径插入：绕过 buffer cache 与部分日志开销，显著提升大批量写入速度 */
    @Override
    public String buildFastInsertSql(String schema, String table, List<String> columns, List<ColumnProfile> profiles) {
        String sql = buildInsertSql(schema, table, columns, profiles);
        return sql.replaceFirst("(?i)INSERT\\s+INTO", "INSERT /*+ APPEND */ INTO");
    }

    /** 直接路径插入会对表加排它锁，必须单会话写入 */
    @Override
    public boolean fastLoadRequiresSingleSession() {
        return true;
    }

    /**
     * 不开并行 DML（不再执行 ALTER SESSION ENABLE PARALLEL DML）。
     * 并行 DML 会让每条语句启动 PX 从属进程并在提交时等待回收，
     * 在 PARALLEL_MAX_SERVERS 紧张或表并行度不合理时会造成长时间阻塞；
     * 收益不稳定、风险高，因此只保留 APPEND 直接路径插入。
     */
    @Override
    public List<String> fastLoadSessionStatements() {
        return Collections.emptyList();
    }

    @Override
    public boolean tableExists(Connection conn, String schema, String table) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        String tableName = normalizeTableName(table);
        String[] schemaCandidates = schemaCandidates(conn, schema);
        for (String candidate : schemaCandidates) {
            try (ResultSet rs = metaData.getTables(null, candidate, tableName, new String[]{"TABLE", "VIEW", "SYNONYM"})) {
                if (rs != null && rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 依次尝试：指定 schema（大写）、连接用户 schema、不限制 schema */
    protected String[] schemaCandidates(Connection conn, String schema) {
        String upper = schema == null || schema.trim().isEmpty() ? null : schema.trim().toUpperCase(Locale.ROOT);
        String userSchema = null;
        try {
            userSchema = conn.getSchema();
        } catch (SQLException | AbstractMethodError ignore) {
            userSchema = null;
        }
        if (userSchema == null || userSchema.trim().isEmpty()) {
            userSchema = upper;
        } else {
            userSchema = userSchema.trim().toUpperCase(Locale.ROOT);
        }
        if (upper == null) {
            return new String[]{userSchema, null};
        }
        if (upper.equals(userSchema)) {
            return new String[]{upper, null};
        }
        return new String[]{upper, userSchema, null};
    }
}
