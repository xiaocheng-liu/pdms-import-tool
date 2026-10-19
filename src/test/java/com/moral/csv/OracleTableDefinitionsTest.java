package com.moral.csv;

import com.moral.db.DialectFactory;
import com.moral.model.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Oracle 导出脚本解析、跨库映射和采样回退的回归测试。 */
public class OracleTableDefinitionsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 注释、默认值和约束中的逗号不得改变字段划分。 */
    @Test public void kingbasePreservesCharacterUnits() {
        assertEquals("VARCHAR(200 CHAR)", OracleColumnType.map("VARCHAR2(200)", DbType.KINGBASE).sql());
        assertEquals("VARCHAR(200 CHAR)", OracleColumnType.map("VARCHAR2(200 CHAR)", DbType.KINGBASE).sql());
        assertEquals("VARCHAR(200 BYTE)", OracleColumnType.map("VARCHAR2(200 BYTE)", DbType.KINGBASE).sql());
    }

    @Test public void parsesExportSyntax() throws Exception {
        var tables = OracleTableDefinitions.parse("""
                -- CREATE TABLE fake(x NUMBER);
                CREATE TABLE "KBE"."DEMO" (
                  "ID" NUMBER(18,4) DEFAULT round(1.2, 1),
                  "CODE" VARCHAR2(50 CHAR) DEFAULT q'[it's (,)]',
                  "AT" DATE, "NOTE" CLOB,
                  CONSTRAINT "PK" PRIMARY KEY ("ID") USING INDEX ENABLE
                );
                COMMENT ON TABLE "KBE"."DEMO" IS 'CREATE TABLE fake(x NUMBER)';
                """);
        assertEquals(1, tables.size());
        assertEquals(4, tables.get("KBE.DEMO").size());
        assertEquals("NUMBER ( 18 , 4 )", tables.get("KBE.DEMO").get("ID"));
    }

    /** Oracle 类型必须转换为各目标库可用的类型，并保留日期时间。 */
    @Test public void mapsAllTargets() {
        for (DbType target : DbType.values()) {
            assertEquals(InferredType.DECIMAL, OracleColumnType.map("NUMBER(18,4)", target).binding());
            assertEquals(InferredType.DATETIME, OracleColumnType.map("DATE", target).binding());
            assertNotNull(OracleColumnType.map("VARCHAR2(50 CHAR)", target));
            assertNotNull(OracleColumnType.map("CLOB", target));
        }
        assertEquals("DECIMAL(18,4)", OracleColumnType.map("NUMBER(18,4)", DbType.MYSQL).sql());
        assertEquals("NUMERIC(20,0)", OracleColumnType.map("NUMBER(20)", DbType.POSTGRESQL).sql());
        assertEquals("DATETIME", OracleColumnType.map("DATE", DbType.MYSQL).sql());
        assertEquals("TIMESTAMP", OracleColumnType.map("DATE", DbType.DAMENG).sql());
        assertEquals("VARCHAR(200 CHAR)", OracleColumnType.map("VARCHAR2(200)", DbType.DAMENG).sql());
        assertEquals("VARCHAR(200 CHAR)", OracleColumnType.map("VARCHAR2(200 CHAR)", DbType.DAMENG).sql());
        assertEquals("VARCHAR(2047 CHAR)", OracleColumnType.map("VARCHAR2(2047)", DbType.DAMENG).sql());
        assertEquals("CLOB", OracleColumnType.map("VARCHAR2(2048)", DbType.DAMENG).sql());
        assertEquals(InferredType.TEXT, OracleColumnType.map("VARCHAR2(4000)", DbType.DAMENG).binding());
        assertEquals("LONGTEXT", OracleColumnType.map("CLOB", DbType.MYSQL).sql());
        assertEquals("NUMERIC(7,0)", OracleColumnType.map("NUMBER(5, - 2)", DbType.KINGBASE).sql());
        assertNull(OracleColumnType.map("TIMESTAMP WITH TIME ZONE", DbType.MYSQL));
    }

    /** 按 CSV 列顺序匹配，数字样本的编码仍按源定义保留为字符串。 */
    @Test public void appliesPrefixedFileAndKeepsCsvOrder() throws Exception {
        var csv = folder.newFile("demo.csv").toPath();
        Files.writeString(csv, "CODE,ID,AT,EXTRA\n001,3.5,2026-10-13,x\n");
        Files.writeString(folder.getRoot().toPath().resolve("kbe_tables.sql"),
                "CREATE TABLE KBE.DEMO(ID NUMBER(18,4), CODE VARCHAR2(50), AT DATE);");
        var meta = CsvMetaReader.read(csv.toFile(), StandardCharsets.UTF_8);
        var dialect = DialectFactory.get(DbType.POSTGRESQL);
        new OracleTableDefinitions().apply(new CsvTable(csv.toFile()), meta, StandardCharsets.UTF_8,
                dialect, "target", s -> {});
        assertEquals(InferredType.STRING, meta.getProfiles().getFirst().getType());
        String ddl = DdlBuilder.build(dialect, "target", "demo", meta);
        assertTrue(ddl.contains("\"code\" VARCHAR(50)"));
        assertTrue(ddl.contains("\"id\" NUMERIC(18,4)"));
        assertTrue(ddl.contains("\"at\" TIMESTAMP"));
        assertTrue(ddl.indexOf("\"code\"") < ddl.indexOf("\"id\""));
        assertNull(meta.getProfiles().get(3).getDeclaredSqlType());
    }

    /** 没有脚本时保留采样；已定义但不支持的类型不允许静默使用采样。 */
    @Test public void missingFileFallsBackButUnsupportedTypeFails() throws Exception {
        var csv = folder.newFile("demo.csv").toPath();
        Files.writeString(csv, "ID\n1\n");
        var meta = CsvMetaReader.read(csv.toFile(), StandardCharsets.UTF_8);
        var dialect = DialectFactory.get(DbType.MYSQL);
        new OracleTableDefinitions().apply(new CsvTable(csv.toFile()), meta, StandardCharsets.UTF_8,
                dialect, "target", s -> {});
        assertNull(meta.getProfiles().getFirst().getDeclaredSqlType());
        Files.writeString(folder.getRoot().toPath().resolve("_tables.sql"), "CREATE TABLE DEMO(ID BLOB);");
        assertThrows(IOException.class, () -> new OracleTableDefinitions().apply(new CsvTable(csv.toFile()),
                meta, StandardCharsets.UTF_8, dialect, "target", s -> {}));
    }

    /** 精确匹配 schema；跨 schema 同名表不随意选择。 */
    @Test public void schemaDisambiguates() throws Exception {
        var csv = folder.newFile("demo.csv").toPath();
        Files.writeString(csv, "ID\n1\n");
        Files.writeString(folder.getRoot().toPath().resolve("_tables.sql"),
                "CREATE TABLE A.DEMO(ID NUMBER(8)); CREATE TABLE B.DEMO(ID VARCHAR2(20));");
        var meta = CsvMetaReader.read(csv.toFile(), StandardCharsets.UTF_8);
        var definitions = new OracleTableDefinitions();
        definitions.apply(new CsvTable(csv.toFile()), meta, StandardCharsets.UTF_8,
                DialectFactory.get(DbType.MYSQL), "C", s -> {});
        assertNull(meta.getProfiles().getFirst().getDeclaredSqlType());
        definitions.apply(new CsvTable(csv.toFile()), meta, StandardCharsets.UTF_8,
                DialectFactory.get(DbType.MYSQL), "B", s -> {});
        assertEquals("VARCHAR(20)", meta.getProfiles().getFirst().getDeclaredSqlType());
    }

    /** 导出的宽表在 MySQL 中使用大文本避免 VARCHAR 总宽度超过行限制。 */
    @Test public void widensMysqlWideTableToText() {
        List<ColumnProfile> profiles = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ColumnProfile p = new ColumnProfile("C" + i);
            p.setDeclaredSqlType("VARCHAR(4000)");
            profiles.add(p);
        }
        var meta = new CsvMeta(profiles.stream().map(ColumnProfile::getName).toList(), profiles);
        assertTrue(DdlBuilder.build(DialectFactory.get(DbType.MYSQL), "target", "demo", meta).contains("LONGTEXT"));
    }

    /** 达梦宽行存储不依赖 CSV 采样，其他数据库不附加达梦语法。 */
    @Test public void enablesDamengLongRowsOnly() {
        ColumnProfile profile = new ColumnProfile("CONTENT_SUMMARY");
        profile.setDeclaredSqlType("VARCHAR(4000)");
        var meta = new CsvMeta(List.of(profile.getName()), List.of(profile));
        for (DbType target : DbType.values()) {
            String ddl = DdlBuilder.build(DialectFactory.get(target), "KB_DOCS", "KB_DOCUMENT", meta);
            assertEquals(target == DbType.DAMENG, ddl.endsWith(" STORAGE(USING LONG ROW)"));
        }
        profile.setDeclaredSqlType(null);
        assertTrue(DdlBuilder.build(DialectFactory.get(DbType.DAMENG), "KB_DOCS", "KB_DOCUMENT", meta)
                .endsWith(" STORAGE(USING LONG ROW)"));
    }

    /** 未提供 Oracle 定义时，达梦采样路径也必须明确字符长度。 */
    @Test public void damengSampledStringsUseCharacterLength() {
        ColumnProfile profile = new ColumnProfile("NAME");
        profile.setMaxLength(200);
        assertEquals("VARCHAR(300 CHAR)", DialectFactory.get(DbType.DAMENG).mapColumnType(profile));
        profile.setMaxLength(2000);
        assertEquals("CLOB", DialectFactory.get(DbType.DAMENG).mapColumnType(profile));
    }

    /** 不完整的导出文件必须明确报错。 */
    @Test public void rejectsTruncatedSql() {
        assertThrows(IOException.class, () -> OracleTableDefinitions.parse("CREATE TABLE T(ID NUMBER(10)"));
        assertThrows(IOException.class, () -> OracleTableDefinitions.parse("/* incomplete"));
    }

    /** 普通字符列补充字符语义，显式单位和国家字符类型保持不变。 */
    @Test public void oracleCharacterSemanticsAreExplicit() {
        assertEquals("VARCHAR2(200 CHAR)", OracleColumnType.map("VARCHAR2(200)", DbType.ORACLE).sql());
        assertEquals("VARCHAR(20 CHAR)", OracleColumnType.map("VARCHAR(20)", DbType.ORACLE).sql());
        assertEquals("CHAR(10 CHAR)", OracleColumnType.map("CHAR(10)", DbType.ORACLE).sql());
        for (String declaration : List.of("VARCHAR2(200 BYTE)", "VARCHAR2(200 CHAR)", "NVARCHAR2(200)", "NCHAR(10)")) {
            assertEquals(declaration, OracleColumnType.map(declaration, DbType.ORACLE).sql());
        }
    }

    /** 243 字节的中文值应能落入 200 字符列，短列不能被采样最小宽度误报。 */
    @Test public void oracleDdlAcceptsMultibyteValuesAndShortColumns() throws Exception {
        var csv = folder.newFile("demo.csv").toPath();
        Files.writeString(csv, "NAME,CODE\n" + "中".repeat(81) + ",A\n");
        Files.writeString(folder.getRoot().toPath().resolve("kb_docs_tables.sql"),
                "CREATE TABLE KB_DOCS.DEMO(NAME VARCHAR2(200), CODE CHAR(1));");
        var meta = CsvMetaReader.read(csv.toFile(), StandardCharsets.UTF_8);
        var dialect = DialectFactory.get(DbType.ORACLE);
        List<String> logs = new ArrayList<>();
        new OracleTableDefinitions().apply(new CsvTable(csv.toFile()), meta, StandardCharsets.UTF_8,
                dialect, "PDMS_TEST", logs::add);
        String ddl = DdlBuilder.build(dialect, "PDMS_TEST", "DEMO", meta);
        assertTrue(ddl.contains("\"PDMS_TEST\".\"DEMO\""));
        assertTrue(ddl.contains("\"NAME\" VARCHAR2(200 CHAR)"));
        assertTrue(ddl.contains("\"CODE\" CHAR(1 CHAR)"));
        assertTrue(logs.stream().anyMatch(s -> s.contains("DEMO.NAME") && s.contains("未指定长度单位")));
    }

    /** 实际字符数超过脚本声明时在建表前报错，不静默截断或扩列。 */
    @Test public void oracleRejectsObservedCharacterOverflow() throws Exception {
        var csv = folder.newFile("demo.csv").toPath();
        Files.writeString(csv, "NAME\n" + "中".repeat(201) + "\n");
        Files.writeString(folder.getRoot().toPath().resolve("kb_docs_tables.sql"),
                "CREATE TABLE DEMO(NAME VARCHAR2(200));");
        var meta = CsvMetaReader.read(csv.toFile(), StandardCharsets.UTF_8);
        IOException error = assertThrows(IOException.class, () -> new OracleTableDefinitions().apply(
                new CsvTable(csv.toFile()), meta, StandardCharsets.UTF_8,
                DialectFactory.get(DbType.ORACLE), "PDMS_TEST", s -> {}));
        assertTrue(error.getMessage().contains("DEMO.NAME"));
        assertTrue(error.getMessage().contains("201"));
    }
}
