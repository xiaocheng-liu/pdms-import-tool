package com.moral.db;

import com.moral.model.ColumnProfile;
import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.model.InferredType;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

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
            case DECIMAL:
                return "NUMERIC";
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
        // 两种引号形式遵循同一个空值选项，非空的 \N 始终作为原文保留。
        String nullOption = emptyAsNull ? "FORCE_NULL" : "FORCE_NOT_NULL";
        return "COPY " + fullyQualified(schema, table) + " (" + columnPart + ") FROM STDIN WITH (FORMAT csv, HEADER "
                + withHeader + ", NULL '', " + nullOption + " (" + columnPart + "))";
    }

    @Override
    public long copyIn(Connection conn, String copySql, InputStream data,
                       LongConsumer bytesConsumer, BooleanSupplier cancelled)
            throws SQLException, IOException {
        return PgCopyLoader.copyIn(conn, copySql, data, bytesConsumer, cancelled);
    }

    /**
     * 判断写入目标是否存在，判定口径必须与写入时使用的限定名完全一致。
     *
     * <p>两条坑（都已在现场出现过）：
     * <ol>
     *   <li>旧实现依次尝试「目标 schema → public → 任意 schema（JDBC 传 null）」，
     *       只要库里任何地方有同名表就判定存在，于是出现"检查说表结构完整、写入却报
     *       关系 kb_docs.xxx 不存在"的矛盾日志；</li>
     *   <li>部分金仓实例（Oracle 兼容模式 / search_path 未包含 pg_catalog）上
     *       {@code pg_class} 不可访问，连 JDBC 的 {@code getTables} 内部都会报
     *       "关系 pg_class 不存在"，异常被吞掉后整段检查形同虚设。</li>
     * </ol>
     *
     * <p>因此这里按"不依赖系统表的手段优先"依次降级，任一手段失败都先回滚再交给下一个手段，
     * 避免派生错误 {@code 当前事务被终止} 掩盖真实原因：直接解析关系 → to_regclass →
     * 带 {@code pg_catalog} 前缀的目录查询 → JDBC 元数据。
     */
    @Override
    public boolean tableExists(Connection conn, String schema, String table) throws SQLException {
        String tableName = normalizeTableName(table);
        String target = schema == null ? "" : schema.trim().toLowerCase(Locale.ROOT);
        List<TableProbe> probes = Arrays.asList(
                this::probeByRelationName,
                this::probeByRegClass,
                this::probeByCatalog,
                this::probeByMetaData);
        List<String> failures = new ArrayList<>();
        for (TableProbe probe : probes) {
            try {
                Boolean result = probe.probe(conn, target, tableName);
                if (result != null) {
                    return result;
                }
            } catch (SQLException e) {
                // PG 系数据库出错后事务被标记 aborted，不回滚的话后续所有手段都只会回一句"当前事务被终止"
                rollbackQuietly(conn);
                failures.add(readableMessage(e));
            }
        }
        throw new SQLException("无法判断表 " + fullyQualified(target, tableName)
                + " 是否存在（已尝试 " + probes.size() + " 种方式）：" + String.join("；", failures));
    }

    /** 表存在性探测手段：返回 true/false 为确定结论，null 表示本手段不可用，交给下一个手段 */
    private interface TableProbe {
        Boolean probe(Connection conn, String schema, String table) throws SQLException;
    }

    /**
     * 首选手段：把写入时使用的限定名直接拿去解析。
     * 不依赖任何系统表，且与写库口径完全一致，因此"能查到 = 能写入"。
     */
    private Boolean probeByRelationName(Connection conn, String schema, String table) throws SQLException {
        String sql = "SELECT 1 FROM " + fullyQualified(schema, table) + " WHERE 1=0";
        try (Statement statement = conn.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.close();
            return Boolean.TRUE;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (isUndefinedRelation(e)) {
                return Boolean.FALSE;
            }
            // 无权限等非结构性错误：本手段无法判定
            throw e;
        }
    }

    /** 42P01 undefined_table：PG / 金仓报告"关系不存在"的 SQLSTATE，无码时按消息兜底识别 */
    private static boolean isUndefinedRelation(SQLException error) {
        for (SQLException each = error; each != null; each = each.getNextException()) {
            String state = each.getSQLState();
            if ("42P01".equals(state)) {
                return true;
            }
            if (state == null) {
                String message = each.getMessage();
                if (message != null && (message.contains("does not exist") || message.contains("不存在"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@link #tableExists} 判定不存在时，返回同名表实际所在的 schema，
     * 用于给出"表存在但不在目标 schema"的可操作提示。纯诊断，失败返回空列表。
     */
    @Override
    public List<String> findTableInOtherSchemas(Connection conn, String schema, String table) throws SQLException {
        List<String> found = new ArrayList<>();
        String target = schema == null ? "" : schema.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            return found;
        }
        String sql = "SELECT n.nspname FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relname::text = ? AND n.nspname::text <> ? ORDER BY n.nspname LIMIT 5";
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, normalizeTableName(table));
            statement.setString(2, target);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
            }
        } catch (SQLException ignore) {
            // 同样要回滚，否则这张连接上的后续语句会一直报"当前事务被终止"
            rollbackQuietly(conn);
            found.clear();
        }
        return found;
    }

    /**
     * 次选手段：内置 {@code to_regclass}，名字查找失败返回 NULL 而不报错，
     * 因此能干净地回答"这个限定名能不能被解析"。带上 {@code pg_catalog} 前缀，
     * 以兼容 search_path 未包含系统 schema 的实例。
     */
    private Boolean probeByRegClass(Connection conn, String schema, String table) throws SQLException {
        String sql = "SELECT pg_catalog.to_regclass(?::text) IS NOT NULL";
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, fullyQualified(schema, table));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getBoolean(1) : null;
            }
        }
    }

    /**
     * 再降级：直接查目录表。显式加 {@code pg_catalog} 前缀（金仓部分模式下裸 pg_class 不可访问），
     * 并把 {@code name}/{@code "char"} 显式转成 text 再与 JDBC 参数比较 —— 金仓对这些类型的
     * 隐式转换不如原生 PG 宽松。未配置 schema 时沿 {@code current_schemas(false)}（生效的 search_path）查找。
     */
    private Boolean probeByCatalog(Connection conn, String schema, String table) throws SQLException {
        String sql = "SELECT 1 FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relname::text = ? AND c.relkind::text IN ('r','p','f') "
                + (schema.isEmpty()
                        ? "AND n.nspname = ANY (pg_catalog.current_schemas(false))"
                        : "AND n.nspname::text = ?")
                + " LIMIT 1";
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, table);
            if (!schema.isEmpty()) {
                statement.setString(2, schema);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** 兜底手段：JDBC 元数据。严格按目标 schema 传 schemaPattern，不再回退到 public / 任意 schema */
    private Boolean probeByMetaData(Connection conn, String schema, String table) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(null, schema.isEmpty() ? null : schema, table,
                new String[]{"TABLE"})) {
            return rs != null && rs.next();
        }
    }

    /** 静默回滚：清理 aborted 事务，保证同一连接上的后续手段还能执行 */
    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            // 回滚失败不中断流程
        }
    }

    /** 错误信息压成单行，便于拼接进汇总异常 */
    private static String readableMessage(SQLException error) {
        String message = error.getMessage();
        if (message == null) {
            return String.valueOf(error.getSQLState());
        }
        return message.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /** PostgreSQL 支持 CREATE SCHEMA IF NOT EXISTS，schema 需与 fullyQualified 一致转小写 */
    @Override
    public String createSchemaIfNotExistsSql(String schema) {
        if (schema == null || schema.trim().isEmpty()) {
            return null;
        }
        return "CREATE SCHEMA IF NOT EXISTS " + quote(schema.trim().toLowerCase(Locale.ROOT));
    }
}
