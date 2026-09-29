package com.moral.db;

import com.moral.model.ColumnProfile;
import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.model.InferredType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * 数据库方言：URL、驱动、标识符大小写与引用、清表语句、类型映射、绑定表达式。
 * 三种数据库的所有差异都收敛在此接口的实现类中。
 */
public interface Dialect {

    DbType type();

    /** JDBC 驱动类名 */
    String driverClass();

    /** 根据连接配置拼装 JDBC URL */
    String buildUrl(ConnectionConfig cfg);

    /** 表名规范化：Oracle/达梦转大写，PG 转小写 */
    String normalizeTableName(String csvName);

    /** 标识符加引号 */
    String quote(String identifier);

    /** schema.table（schema 为空时只返回表名） */
    String fullyQualified(String schema, String table);

    String truncateSql(String schema, String table);

    String deleteSql(String schema, String table);

    /**
     * 生成 INSERT 语句，时间列按方言使用不同的绑定表达式。
     *
     * @param columns  CSV 表头列名
     * @param profiles 每列的采样类型（与 columns 一一对应）
     */
    String buildInsertSql(String schema, String table, List<String> columns, List<ColumnProfile> profiles);

    /**
     * 时间类列的绑定表达式：三种库统一使用普通占位符，由驱动按目标列类型转换 Timestamp。
     * （不要拼接 TO_DATE/TO_TIMESTAMP：绑定值本身已是 Timestamp，再套日期格式函数会触发
     * Oracle 的隐式字符串转换，在非默认 NLS 日期格式下报 ORA-01858。）
     */
    String timestampBindExpr(InferredType type);

    /** 列类型 DDL 映射 */
    String mapColumnType(ColumnProfile profile);

    /** 判断表是否存在 */
    boolean tableExists(Connection conn, String schema, String table) throws SQLException;

    /**
     * 目标 schema 不存在时的自动创建语句（如 PostgreSQL 的 {@code CREATE SCHEMA IF NOT EXISTS}）。
     * 返回 null 表示该库不需要或不支持在此自动创建（Oracle、达梦的 schema 即用户，无法自动创建）。
     */
    default String createSchemaIfNotExistsSql(String schema) {
        return null;
    }

    // ==================== 极速模式（可选） ====================

    /** 是否支持极速加载路径 */
    default boolean supportsFastLoad() {
        return false;
    }

    /**
     * 极速路径使用的插入语句。Oracle/达梦通过 APPEND 提示走直接路径插入，
     * 默认与标准 INSERT 相同。
     */
    default String buildFastInsertSql(String schema, String table, List<String> columns, List<ColumnProfile> profiles) {
        return buildInsertSql(schema, table, columns, profiles);
    }

    /**
     * 极速路径要求单会话（单分片）写入，例如 Oracle 的直接路径插入会对表加排它锁，
     * 多分片并行写同一张表会互相阻塞。
     */
    default boolean fastLoadRequiresSingleSession() {
        return false;
    }

    /** 极速路径的会话级提速语句（如 Oracle 打开并行 DML），默认为空 */
    default List<String> fastLoadSessionStatements() {
        return Collections.emptyList();
    }

    /**
     * 生成 COPY 批量加载语句（PostgreSQL）；返回 null 表示该方言不使用 COPY。
     *
     * @param emptyAsNull 空字段是否作为 NULL
     * @param withHeader  数据流首行是否为表头（需要跳过）
     */
    default String buildCopyInSql(String schema, String table, List<String> columns,
                                  boolean emptyAsNull, boolean withHeader) {
        return null;
    }
}
