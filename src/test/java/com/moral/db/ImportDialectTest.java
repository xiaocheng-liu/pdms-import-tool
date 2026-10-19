package com.moral.db;

import com.moral.csv.CsvMetaReader;
import com.moral.csv.DdlBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/** 采样建表的小数保留和 COPY 空值选项回归测试。 */
public class ImportDialectTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 合法小数的采样 DDL 不能隐式指定零位小数。 */
    @Test public void oracleSampledDecimalsUseUnconstrainedNumber() throws Exception {
        var file = folder.newFile("AMOUNTS.csv").toPath();
        Files.writeString(file, "AMOUNT\n12.34\n-0.000123\n1.234e-10\n");
        var meta = CsvMetaReader.read(file.toFile(), StandardCharsets.UTF_8);
        assertEquals("CREATE TABLE \"KBE\".\"AMOUNTS\" (\n  \"AMOUNT\" NUMBER\n)",
                DdlBuilder.build(new OracleDialect(), "KBE", "AMOUNTS", meta));
        meta.getProfiles().getFirst().setDeclaredSqlType("NUMBER(18,4)");
        assertTrue(DdlBuilder.build(new OracleDialect(), "KBE", "AMOUNTS", meta).contains("NUMBER(18,4)"));
    }

    /** 空值开关同时覆盖带引号和不带引号的空字段，并逐列指定作用范围。 */
    @Test public void copyNullOptionCoversEveryColumnAndShard() {
        for (PgDialect dialect : List.of(new PgDialect(), new KingbaseDialect())) {
            for (boolean header : new boolean[]{true, false}) {
                String sql = dialect.buildCopyInSql("KBE", "DEMO", List.of("CODE", "NOTE"), true, header);
                assertEquals("COPY " + dialect.fullyQualified("KBE", "DEMO") + " (\"code\", \"note\") FROM STDIN WITH (FORMAT csv, HEADER "
                        + header + ", NULL '', FORCE_NULL (\"code\", \"note\"))", sql);
            }
        }
    }

    /** 不转换空值时保留空字符串和字面量反斜杠 N，不能占用业务值作为 NULL 标记。 */
    @Test public void copyPreservesEmptyStringsWhenNullConversionDisabled() {
        for (PgDialect dialect : List.of(new PgDialect(), new KingbaseDialect())) {
            String sql = dialect.buildCopyInSql("KBE", "DEMO", List.of("VALUE"), false, true);
            assertTrue(sql.contains("NULL '', FORCE_NOT_NULL (\"value\")"));
            assertFalse(sql.contains("\\N"));
        }
    }
}
