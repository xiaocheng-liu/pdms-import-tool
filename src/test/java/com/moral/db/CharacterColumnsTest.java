package com.moral.db;

import com.moral.model.DbType;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 五种数据库的目录长度读取及扩长 SQL 回归测试。 */
public class CharacterColumnsTest {

    /** 大字段目录可能返回数据长度，但类型展示不应拼出 CLOB(4000)。 */
    @Test public void largeObjectDisplayOmitsCatalogLength() {
        CharacterColumns.Column clob = new CharacterColumns.Column("NOTE", "CLOB", 4000, "", "");
        CharacterColumns.Column nclob = new CharacterColumns.Column("NOTE", "NCLOB", 4000, "", "");
        assertEquals("CLOB", clob.display());
        assertEquals("NCLOB", nclob.display());
    }

    /** Oracle/达梦按 CHAR_USED 选择字符容量或字节容量，国家字符列始终按字符。 */
    @Test public void oracleAndDamengReadLengthUnits() throws Exception {
        for (DbType type : List.of(DbType.ORACLE, DbType.DAMENG)) {
            List<String> queries = new ArrayList<>();
            var connection = connection(queries, List.of(
                    new Object[]{"CODE", "VARCHAR2", 400L, 100L, "C"},
                    new Object[]{"BYTES", "VARCHAR", 200L, 50L, "B"},
                    new Object[]{"NATIONAL", "NVARCHAR2", 200L, 100L, null}), null);
            var table = CharacterColumns.read(connection, DialectFactory.get(type), "kbe", "demo");
            assertEquals("KBE", table.schema());
            assertEquals(100, table.columns().get("CODE").length());
            assertEquals(200, table.columns().get("BYTES").length());
            assertEquals("BYTE", table.columns().get("BYTES").unit());
            assertEquals("CHAR", table.columns().get("NATIONAL").unit());
            assertTrue(queries.getFirst().contains("ALL_TAB_COLUMNS"));
        }
    }

    /** PG 系使用 character_maximum_length，域类型不自动变成基础 VARCHAR。 */
    @Test public void postgresAndKingbaseReadCharacterLength() throws Exception {
        for (DbType type : List.of(DbType.POSTGRESQL, DbType.KINGBASE)) {
            var connection = connection(new ArrayList<>(), List.of(
                    new Object[]{"code", "character varying", 100L, null, "CHAR"},
                    new Object[]{"flag", "character", 2L, null, "BYTE"},
                    new Object[]{"domain_code", "character varying", 100L, "code_domain", "CHAR"}), null);
            var table = CharacterColumns.read(connection, DialectFactory.get(type), "KBE", "DEMO");
            assertEquals(type == DbType.KINGBASE ? "KBE" : "kbe", table.schema());
            assertEquals(type == DbType.KINGBASE ? "BYTE" : "CHAR", table.columns().get("flag").unit());
            assertEquals("VARCHAR", table.columns().get("code").type());
            assertEquals("CHAR", table.columns().get("flag").type());
            assertFalse(table.columns().get("domain_code").isCharacter());
        }
    }

    /** MySQL 使用当前 catalog，完整保留默认值、注释、排序规则和表达式中的逗号。 */
    @Test public void mysqlKeepsFullDefinition() throws Exception {
        String definition = "`code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL "
                + "DEFAULT 'a,b)' COMMENT '备注,(x)'";
        String ddl = "CREATE TABLE `demo` (\n" + definition + ",\n"
                + "`note` varchar(100) GENERATED ALWAYS AS (concat(`code`,',x')) STORED,\nPRIMARY KEY (`code`)) ENGINE=InnoDB";
        var connection = connection(new ArrayList<>(), List.<Object[]>of(new Object[]{"code", "varchar", 100L, "utf8mb4", "utf8mb4_bin"}), ddl);
        var table = CharacterColumns.read(connection, new MySQLDialect(), "ignored", "demo");
        assertEquals("actual_db", table.schema());
        var column = table.columns().get("code");
        assertEquals(definition, column.definition());
        assertEquals("ALTER TABLE `actual_db`.`demo` MODIFY COLUMN " + definition.replace("varchar(100)", "varchar(500)"),
                CharacterColumns.widenSql(new MySQLDialect(), table.schema(), table.name(), column, 500));
        assertEquals(2, CharacterColumns.mysqlDefinitions(ddl).size());
    }

    /** 生成的语句保留字符类型与长度单位，Oracle 明确使用 schema.table。 */
    @Test public void rendersEachDialect() {
        for (DbType type : DbType.values()) {
            var dialect = DialectFactory.get(type);
            var column = new CharacterColumns.Column("CODE", "VARCHAR", 100, "BYTE", "`CODE` varchar(100) NOT NULL");
            String sql = CharacterColumns.widenSql(dialect, "KBE", "DEMO", column, 500);
            switch (type) {
                case ORACLE, DAMENG -> assertEquals("ALTER TABLE \"KBE\".\"DEMO\" MODIFY (\"CODE\" VARCHAR(500 BYTE))", sql);
                case KINGBASE -> assertEquals("ALTER TABLE \"KBE\".\"DEMO\" ALTER COLUMN \"CODE\" TYPE VARCHAR(500 BYTE)", sql);
                case POSTGRESQL -> assertEquals("ALTER TABLE \"KBE\".\"DEMO\" ALTER COLUMN \"CODE\" TYPE VARCHAR(500)", sql);
                case MYSQL -> assertEquals("ALTER TABLE `KBE`.`DEMO` MODIFY COLUMN `CODE` varchar(500) NOT NULL", sql);
            }
        }
    }

    /** 不能解析完整 MySQL 定义时，不猜测默认值或列属性。 */
    @Test public void malformedMysqlDefinitionIsNotAltered() {
        assertTrue(CharacterColumns.mysqlDefinitions("CREATE TABLE t (`x` varchar(100) COMMENT 'bad)").isEmpty());
        var column = new CharacterColumns.Column("x", "VARCHAR", 100, "CHAR", "`x` text");
        assertNull(CharacterColumns.widenSql(new MySQLDialect(), "db", "t", column, 500));
    }

    /** MySQL 属性变化必须使预览基线失效，仅长度变化可继续比较。 */
    @Test public void detectsMysqlAttributeChanges() {
        var before = new CharacterColumns.Column("x", "VARCHAR", 100, "CHAR", "`x` varchar(100) DEFAULT 'a'");
        var grown = new CharacterColumns.Column("x", "VARCHAR", 500, "CHAR", "`x` varchar(500) DEFAULT 'a'");
        var changed = new CharacterColumns.Column("x", "VARCHAR", 100, "CHAR", "`x` varchar(100) DEFAULT 'b'");
        assertTrue(CharacterColumns.sameDefinition(before, grown));
        assertFalse(CharacterColumns.sameDefinition(before, changed));
    }

    /** SHOW CREATE 补出省略的字符集和排序规则不代表列属性变化。 */
    @Test public void acceptsEquivalentMysqlCharacterAttributes() {
        for (String attributes : List.of("", " COLLATE utf8mb4_bin", " CHARACTER SET utf8mb4",
                " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")) {
            var before = new CharacterColumns.Column("x", "VARCHAR", 100, "CHAR",
                    "`x` varchar(100)" + attributes + " DEFAULT NULL COMMENT 'COLLATE stays literal'", "utf8mb4", "utf8mb4_bin");
            var after = new CharacterColumns.Column("x", "VARCHAR", 500, "CHAR",
                    "`x` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'COLLATE stays literal'",
                    "utf8mb4", "utf8mb4_bin");
            assertTrue(CharacterColumns.sameDefinition(before, after));
            assertEquals("ALTER TABLE `db`.`t` MODIFY COLUMN " + after.definition(),
                    CharacterColumns.widenSql(new MySQLDialect(), "db", "t", before, 500));
        }
    }

    /** 有效字符属性真正变化时，即使省略后的文本相同也不能通过复核。 */
    @Test public void rejectsChangedEffectiveMysqlAttributes() {
        var before = new CharacterColumns.Column("x", "VARCHAR", 100, "CHAR", "`x` varchar(100) DEFAULT NULL",
                "utf8mb4", "utf8mb4_bin");
        for (var after : List.of(
                new CharacterColumns.Column("x", "VARCHAR", 500, "CHAR", "`x` varchar(500) DEFAULT NULL", "latin1", "latin1_bin"),
                new CharacterColumns.Column("x", "VARCHAR", 500, "CHAR", "`x` varchar(500) DEFAULT NULL", "utf8mb4", "utf8mb4_general_ci"),
                new CharacterColumns.Column("x", "VARCHAR", 500, "CHAR", "`x` varchar(500) DEFAULT 'changed'", "utf8mb4", "utf8mb4_bin"),
                new CharacterColumns.Column("x", "VARCHAR", 500, "CHAR", "`x` varchar(500) NOT NULL", "utf8mb4", "utf8mb4_bin"))) {
            assertFalse(CharacterColumns.sameDefinition(before, after));
        }
    }

    /** 目录和 SHOW CREATE 不一致时不生成 SQL，避免跨查询变化导致错误属性写回。 */
    @Test public void inconsistentMysqlMetadataIsNotRewritten() {
        var column = new CharacterColumns.Column("x", "VARCHAR", 100, "CHAR",
                "`x` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL", "utf8mb4", "utf8mb4_bin");
        assertNull(CharacterColumns.widenSql(new MySQLDialect(), "db", "t", column, 500));
    }

    /** 带括号的引用列名不干扰字符类型边界。 */
    @Test public void mysqlColumnNameParenthesesArePreserved() {
        var column = new CharacterColumns.Column("x(y)", "VARCHAR", 100, "CHAR",
                "`x(y)` varchar(100) DEFAULT NULL", "utf8mb4", "utf8mb4_bin");
        assertEquals("ALTER TABLE `db`.`t` MODIFY COLUMN `x(y)` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL",
                CharacterColumns.widenSql(new MySQLDialect(), "db", "t", column, 500));
    }

    /** 目录查询没有任何列时必须报错。 */
    @Test public void missingMetadataFails() {
        assertThrows(SQLException.class, () -> CharacterColumns.read(
                connection(new ArrayList<>(), List.of(), null), new OracleDialect(), "KBE", "DEMO"));
    }

    /** JDBC 目录替身，记录查询并返回指定元数据。 */
    private static Connection connection(List<String> queries, List<Object[]> rows, String createSql) {
        return proxy(Connection.class, (method, args) -> switch (method) {
            case "getCatalog" -> "actual_db";
            case "prepareStatement" -> {
                queries.add((String) args[0]);
                yield proxy(PreparedStatement.class, (name, params) -> name.equals("executeQuery") ? rows(rows) : null);
            }
            case "createStatement" -> proxy(Statement.class, (name, params) -> {
                if (name.equals("executeQuery")) return rows(List.<Object[]>of(new Object[]{"demo", createSql}));
                return null;
            });
            default -> null;
        });
    }

    /** 顺序结果集，模拟 JDBC 的 wasNull 行为。 */
    private static ResultSet rows(List<Object[]> data) {
        int[] index = {-1};
        boolean[] wasNull = {false};
        return proxy(ResultSet.class, (method, args) -> {
            if (method.equals("next")) return ++index[0] < data.size();
            if (method.equals("wasNull")) return wasNull[0];
            if (method.equals("getString") || method.equals("getLong")) {
                Object value = data.get(index[0])[(int) args[0] - 1];
                wasNull[0] = value == null;
                return method.equals("getString") ? value : value == null ? 0L : ((Number) value).longValue();
            }
            return null;
        });
    }

    /** 最小 JDBC 代理，不加载数据库服务。 */
    private static <T> T proxy(Class<T> type, Invocation call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> call.invoke(method.getName(), args)));
    }

    @FunctionalInterface private interface Invocation { Object invoke(String method, Object[] args) throws Exception; }
}
