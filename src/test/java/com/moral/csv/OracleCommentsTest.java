package com.moral.csv;

import com.moral.model.CsvTable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Oracle 表和列注释的解析、Schema 匹配与脚本合并测试。 */
public class OracleCommentsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 引号、分号、换行和 q 字符串均作为正文，普通行注释不执行。 */
    @Test public void parsesQuotedAndAlternativeLiterals() throws Exception {
        var comments = OracleTableDefinitions.parseComments("""
                -- COMMENT ON TABLE FAKE IS 'ignore';
                COMMENT ON TABLE "KBE"."DEMO" IS '分类; O''Brien';
                COMMENT ON COLUMN "KBE"."DEMO"."CODE" IS q'[编码's
                第二行]';
                COMMENT ON COLUMN KBE.DEMO.NOTE IS NULL;
                """).get("KBE.DEMO");
        assertEquals("分类; O'Brien", comments.table());
        assertEquals("编码's\n第二行", comments.columns().get("CODE"));
        assertEquals("", comments.columns().get("NOTE"));
    }

    /** 重复、损坏或表达式注释不允许静默通过。 */
    @Test public void rejectsAmbiguousOrMalformedComments() {
        for (String sql : new String[]{"COMMENT ON TABLE DEMO IS 'x'; COMMENT ON TABLE DEMO IS 'y';",
                "COMMENT ON COLUMN DEMO.CODE IS 'x'; COMMENT ON COLUMN DEMO.CODE IS 'x';",
                "COMMENT ON COLUMN DEMO IS 'x';", "COMMENT ON TABLE DEMO IS concat('x','y');",
                "COMMENT ON TABLE DEMO IS 'broken"}) {
            assertThrows(IOException.class, () -> OracleTableDefinitions.parseComments(sql));
        }
    }

    /** 同目录分开的建表和注释脚本可合并，并优先匹配指定 Schema。 */
    @Test public void mergesCommentFilesAndMatchesSchema() throws Exception {
        var csv = folder.newFile("demo.csv");
        Files.writeString(csv.toPath(), "CODE\nx\n");
        Files.writeString(folder.getRoot().toPath().resolve("a_tables.sql"), "CREATE TABLE KBE.DEMO(CODE VARCHAR2(20));");
        Files.writeString(folder.getRoot().toPath().resolve("b_tables.sql"),
                "COMMENT ON TABLE KBE.DEMO IS '目标'; COMMENT ON TABLE OTHER.DEMO IS '其他';"
                        + "COMMENT ON COLUMN KBE.DEMO.CODE IS '编码';");
        var definitions = new OracleTableDefinitions();
        var table = new CsvTable(csv);
        assertEquals("目标", definitions.comments(table, StandardCharsets.UTF_8, "KBE", s -> {}).table());
        assertEquals("编码", definitions.comments(table, StandardCharsets.UTF_8, "KBE", s -> {}).columns().get("CODE"));
        assertEquals(1, definitions.columns(table, StandardCharsets.UTF_8, "KBE", s -> {}).size());
        assertTrue(definitions.comments(table, StandardCharsets.UTF_8, "UNKNOWN", s -> {}).isEmpty());
    }
}
