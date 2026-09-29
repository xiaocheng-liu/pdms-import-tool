package com.moral.db;

import com.moral.model.ColumnProfile;
import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.model.InferredType;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * MySQL 方言：标识符小写、反引号引用、库即 schema（不做自动建库）。
 *
 * <p>与 PostgreSQL 同源的大小写策略（转小写），但引用符是反引号；
 * MySQL 没有 schema 概念，连接串里的库名就是 schema，因此：
 * <ul>
 *   <li>{@link #createSchemaIfNotExistsSql} 用默认实现（null），库需现场事先建好；</li>
 *   <li>{@link #tableExists} 以当前连接库（catalog）为准判断表是否存在；</li>
 *   <li>极速模式不走 COPY（MySQL 没有），走 {@code LOAD DATA LOCAL INFILE}。</li>
 * </ul>
 */
public class MySQLDialect implements Dialect {

    /** LOAD DATA 语句里文件名只是占位：数据实际来自驱动的输入流 */
    private static final String INFILE_PLACEHOLDER = "pdms-import-stream";
    /** VARCHAR 长度上限：MySQL 单行 65535 字节，utf8mb4 每字符 4 字节，宽表必须控制列宽 */
    private static final int MAX_VARCHAR = 255;

    @Override
    public DbType type() {
        return DbType.MYSQL;
    }

    @Override
    public String driverClass() {
        return DbType.MYSQL.getDriverClass();
    }

    @Override
    public String buildUrl(ConnectionConfig cfg) {
        return "jdbc:mysql://" + cfg.getHost() + ":" + cfg.getPort() + "/" + cfg.getDatabase().trim()
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&connectTimeout=15000"
                // 允许 LOAD DATA LOCAL INFILE（8.0 默认关闭），缺了它极速模式必然降级
                + "&allowLoadLocalInfile=true"
                // 把 addBatch 改写成多值 INSERT，标准模式也能明显提速
                + "&rewriteBatchedStatements=true";
    }

    @Override
    public String normalizeTableName(String csvName) {
        return csvName == null ? null : csvName.toLowerCase(Locale.ROOT);
    }

    @Override
    public String quote(String identifier) {
        return "`" + identifier + "`";
    }

    /**
     * MySQL 的库就是 schema，连接串里的库名已确定写入位置，
     * 因此这里<b>忽略</b> schema 参数：避免沿用 Oracle/PG 习惯填入的 schema 值被拼成 `库`.`表` 而写错库。
     */
    @Override
    public String fullyQualified(String schema, String table) {
        return quote(normalizeTableName(table));
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

    /** 时间列直接用 setTimestamp 绑定，MySQL 会按目标列类型（DATE / DATETIME）转换 */
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
                return "DOUBLE";
            case DATE:
                return "DATE";
            case DATETIME:
                // 不用 TIMESTAMP：避免 2038 限制与隐式默认值/自动更新带来的写入差异
                return "DATETIME";
            case TEXT:
                return "TEXT";
            default:
                int length = Math.max(1, (int) Math.ceil(profile.getMaxLength() * 1.5));
                // 超长文本直接落 TEXT：VARCHAR 太宽会让宽表触发 ER_TOO_BIG_ROWSIZE(1118)
                return length <= MAX_VARCHAR ? "VARCHAR(" + length + ")" : "TEXT";
        }
    }

    @Override
    public boolean supportsFastLoad() {
        return true;
    }

    /** 极速模式：LOAD DATA LOCAL INFILE，由服务端解析 CSV */
    @Override
    public String buildLoadDataSql(String schema, String table, List<String> columns,
                                   boolean emptyAsNull, boolean withHeader) {
        StringBuilder sql = new StringBuilder();
        sql.append("LOAD DATA LOCAL INFILE '").append(INFILE_PLACEHOLDER).append("' INTO TABLE ")
                .append(fullyQualified(schema, table))
                .append(" CHARACTER SET utf8mb4")
                .append(" FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"' ESCAPED BY ''")
                .append(" LINES TERMINATED BY '\\n'");
        if (withHeader) {
            sql.append(" IGNORE 1 LINES");
        }
        if (emptyAsNull) {
            // 空串转 NULL：先读入用户变量，再用 NULLIF 赋值
            for (int i = 0; i < columns.size(); i++) {
                sql.append(i == 0 ? " (@v" : ", @v").append(i);
            }
            sql.append(") SET ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(quote(normalizeTableName(columns.get(i))))
                        .append(" = NULLIF(@v").append(i).append(", '')");
            }
            return sql.toString();
        }
        sql.append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(quote(normalizeTableName(columns.get(i))));
        }
        return sql.append(")").toString();
    }

    @Override
    public long loadLocalInfile(Connection conn, String sql, InputStream data,
                                LongConsumer bytesConsumer, BooleanSupplier cancelled,
                                Consumer<Statement> statementHook)
            throws SQLException, IOException {
        return MySqlLoadDataLoader.loadLocalInfile(conn, sql, data, bytesConsumer, cancelled, statementHook);
    }

    /** MySQL 的库就是 schema：以当前连接库（catalog）判断表是否存在 */
    @Override
    public boolean tableExists(Connection conn, String schema, String table) throws SQLException {
        String tableName = normalizeTableName(table);
        String catalog = null;
        try {
            catalog = conn.getCatalog();
        } catch (SQLException ignore) {
            catalog = null;
        }
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(catalog, null, tableName, new String[]{"TABLE"})) {
            return rs != null && rs.next();
        }
    }
}
