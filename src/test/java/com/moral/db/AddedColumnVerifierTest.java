package com.moral.db;

import com.moral.csv.OracleColumnType;
import com.moral.model.DbType;
import com.moral.model.SchemaPlan;
import org.junit.Test;

import java.sql.*;

import static org.junit.Assert.*;

/** 新增列验证的五种方言目录和 JDBC 精度测试，不连接真实数据库。 */
public class AddedColumnVerifierTest {
    /** 五种方言的转换后字符类型均按目录长度和单位验证。 */
    @Test public void verifiesCharacterTypesAcrossDialects() throws Exception {
        for (DbType type : DbType.values()) {
            Fixture db = new Fixture(type);
            String declaration = OracleColumnType.map("VARCHAR2(100)", type).sql();
            db.actualType = type == DbType.ORACLE ? "VARCHAR2" : "VARCHAR";
            db.verify(declaration);
            db.length = 50;
            assertThrows(SQLException.class, () -> db.verify(declaration));
        }
    }

    /** 精度和小数位必须与已确认的数值定义一致。 */
    @Test public void verifiesNumericPrecisionAcrossDialects() throws Exception {
        for (DbType type : DbType.values()) {
            Fixture db = new Fixture(type);
            String declaration = OracleColumnType.map("NUMBER(12,2)", type).sql();
            db.actualType = type == DbType.ORACLE || type == DbType.DAMENG ? "NUMBER" : "NUMERIC";
            db.verify(declaration);
            db.scale = 0;
            assertThrows(SQLException.class, () -> db.verify(declaration));
            db.scale = 2; db.precision = 10;
            assertThrows(SQLException.class, () -> db.verify(declaration));
        }
    }

    /** 目录时间类型别名及大字段可验证，不依赖字符长度。 */
    @Test public void verifiesTemporalAndLobTypesAcrossDialects() throws Exception {
        for (DbType type : DbType.values()) {
            Fixture db = new Fixture(type);
            db.actualType = switch (type) {
                case ORACLE -> "DATE";
                case DAMENG -> "TIMESTAMP(6)";
                case MYSQL -> "DATETIME";
                default -> "timestamp without time zone";
            };
            db.verify(OracleColumnType.map("DATE", type).sql());
            db.actualType = switch (type) {
                case ORACLE, DAMENG -> "CLOB";
                case MYSQL -> "LONGTEXT";
                default -> "TEXT";
            };
            db.verify(OracleColumnType.map("CLOB", type).sql());
        }
    }

    /** 类型、长度单位、可空性或列本身不符时停止，不将验证失败当成成功。 */
    @Test public void rejectsUnexpectedDefinitions() {
        for (int mode = 0; mode < 4; mode++) {
            Fixture db = new Fixture(DbType.ORACLE);
            db.actualType = mode == 0 ? "CHAR" : "VARCHAR2";
            db.unit = mode == 1 ? "B" : "C";
            db.nullable = mode == 2 ? ResultSetMetaData.columnNoNulls : ResultSetMetaData.columnNullable;
            db.name = mode == 3 ? "OTHER" : "CODE";
            assertThrows(SQLException.class, () -> db.verify("VARCHAR2(100 CHAR)"));
        }
    }

    /** 可变目录替身，精度使用 JDBC 元数据，字符容量使用目录。 */
    private static final class Fixture {
        final DbType type;
        String name = "CODE", actualType = "VARCHAR", unit = "C";
        int length = 100, precision = 12, scale = 2, nullable = ResultSetMetaData.columnNullable;

        Fixture(DbType type) { this.type = type; }

        /** 执行独立验证器，所有查询返回内存元数据。 */
        void verify(String declaration) throws SQLException {
            Dialect dialect = DialectFactory.get(type);
            String column = dialect.normalizeTableName("CODE");
            Connection connection = proxy(Connection.class, (method, args) -> {
                if (method.equals("getCatalog")) return "KBE";
                if (method.equals("prepareStatement")) return proxy(PreparedStatement.class, (op, params) -> {
                    if (!op.equals("executeQuery")) return null;
                    int[] index = {0};
                    return proxy(ResultSet.class, (read, values) -> switch (read) {
                        case "next" -> index[0]++ == 0;
                        case "wasNull" -> false;
                        case "getLong" -> (long) length;
                        case "getString" -> switch ((int) values[0]) {
                            case 1 -> dialect.normalizeTableName(name);
                            case 2 -> actualType;
                            case 4 -> type == DbType.MYSQL ? "utf8mb4" : null;
                            case 5 -> type == DbType.MYSQL ? "utf8mb4_bin" : unit;
                            default -> null;
                        };
                        default -> null;
                    });
                });
                if (method.equals("createStatement")) return proxy(Statement.class, (op, params) -> {
                    if (!op.equals("executeQuery")) return null;
                    int[] index = {0};
                    var metadata = proxy(ResultSetMetaData.class, (read, values) -> switch (read) {
                        case "getColumnCount" -> 1;
                        case "isNullable" -> nullable;
                        case "getPrecision" -> precision;
                        case "getScale" -> scale;
                        default -> null;
                    });
                    return proxy(ResultSet.class, (read, values) -> switch (read) {
                        case "next" -> index[0]++ == 0;
                        case "getString" -> "CREATE TABLE `demo` (`" + column + "` varchar(" + length + ") NULL)";
                        case "getMetaData" -> metadata;
                        default -> null;
                    });
                });
                return null;
            });
            AddedColumnVerifier.verify(connection, dialect, new SchemaPlan.Addition("KBE", "DEMO", column, declaration, ""));
        }
    }
    /** 最小 JDBC 代理，用于提供目录和列属性。 */
    private static <T> T proxy(Class<T> type, Invocation call) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> call.invoke(method.getName(), args)));
    }

    @FunctionalInterface private interface Invocation { Object invoke(String method, Object[] args) throws Exception; }

}
