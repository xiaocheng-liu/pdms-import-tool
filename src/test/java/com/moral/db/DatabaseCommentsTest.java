package com.moral.db;

import com.moral.model.DbType;
import org.junit.Test;

import java.sql.SQLException;
import static org.junit.Assert.*;

/** 注释 SQL 的方言、转义及完整列属性保留测试。 */
public class DatabaseCommentsTest {
    /** 注释语句始终限定 Schema 和目标列。 */
    @Test public void generatesAllDialectComments() throws Exception {
        for (DbType type : DbType.values()) {
            Dialect dialect = DialectFactory.get(type);
            String literal = DatabaseComments.literal(dialect, "名称's", false);
            String sql = DatabaseComments.sql(dialect, "KBE", "DEMO", "", literal);
            assertTrue(sql.contains(CharacterColumns.qualified(dialect, "KBE", "DEMO")));
            assertTrue(sql.contains("名称''s"));
            if (type != DbType.MYSQL) assertTrue(DatabaseComments.sql(dialect, "KBE", "DEMO", "CODE", literal)
                    .startsWith("COMMENT ON COLUMN \"KBE\".\"DEMO\".\"CODE\" IS "));
            else assertTrue(sql.startsWith("ALTER TABLE"));
        }
    }

    /** MySQL 会话转义和 PG E 字符串保留反斜杠及引号。 */
    @Test public void escapesWithoutChangingText() throws Exception {
        assertEquals("'a\\\\b''c'", DatabaseComments.literal(new MySQLDialect(), "a\\b'c", false));
        assertEquals("'a\\b''c'", DatabaseComments.literal(new MySQLDialect(), "a\\b'c", true));
        assertEquals("E'a\\\\b''c'", DatabaseComments.literal(new PgDialect(), "a\\b'c", false));
        assertEquals("'a\\b''c'", DatabaseComments.literal(new OracleDialect(), "a\\b'c", false));
        assertThrows(SQLException.class, () -> DatabaseComments.literal(new OracleDialect(), "x\0y", false));
    }

    /** 默认值、表达式和注释正文中的 COMMENT 不作为属性替换。 */
    @Test public void mysqlOnlyReplacesCommentLiteral() throws Exception {
        String before = "`code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL "
                + "DEFAULT 'COMMENT \\'a\\'' COMMENT 'old' INVISIBLE";
        assertEquals(before.replace("COMMENT 'old'", "COMMENT '新的''注释'"),
                DatabaseComments.mysqlColumn(before, "'新的''注释'", false));
        String generated = "`note` varchar(200) GENERATED ALWAYS AS (concat('COMMENT', `code`)) STORED";
        assertEquals(generated + " COMMENT '说明'", DatabaseComments.mysqlColumn(generated, "'说明'", false));
        String numeric = "`status` decimal(1,0) NOT NULL DEFAULT '0'";
        assertEquals(numeric + " COMMENT '状态'", DatabaseComments.mysqlColumn(numeric, "'状态'", false));
    }

    /** 不转义模式下以反斜杠结尾的注释仍可安全解析。 */
    @Test public void mysqlNoBackslashModeHandlesTrailingSlash() throws Exception {
        String definition = "`code` varchar(100) COMMENT 'path\\'";
        assertEquals("`code` varchar(100) COMMENT 'new'", DatabaseComments.mysqlColumn(definition, "'new'", true));
        assertEquals(definition, CharacterColumns.mysqlDefinitions("CREATE TABLE t (" + definition + ")", true).get("code"));
    }

    /** 无法可靠解析列定义时拒绝生成注释 SQL。 */
    @Test public void rejectsMalformedMysqlComments() {
        for (String definition : new String[]{"`x` varchar(10) COMMENT 'bad", "`x` int COMMENT 'a' COMMENT 'b'",
                "`x` int COMMENT concat('a','b')"}) {
            assertThrows(SQLException.class, () -> DatabaseComments.mysqlColumn(definition, "'new'", false));
        }
    }
}
