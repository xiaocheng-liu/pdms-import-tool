package com.moral.task;

import com.moral.csv.CsvMeta;
import com.moral.csv.CsvMetaReader;
import com.moral.db.DialectFactory;
import com.moral.db.OracleDialect;
import com.moral.db.TargetColumnTypes;
import com.moral.model.ColumnProfile;
import com.moral.model.DbType;
import com.moral.model.InferredType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.moral.task.BatchingWriterTest.proxy;
import static org.junit.Assert.*;

/** 目标表元数据与真实参数绑定的回归测试，数据库调用使用 JDBC 替身。 */
public class TargetColumnTypesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 已有字符列不能因前 5000 行为数字而拒绝后续合法编码。 */
    @Test public void existingCharacterColumnKeepsLateLeadingZeros() throws Exception {
        var file = folder.newFile("DEMO.csv").toPath();
        Files.writeString(file, "CODE\n" + "123\n".repeat(5000) + "00123\n");
        CsvMeta meta = CsvMetaReader.read(file.toFile(), StandardCharsets.UTF_8);
        assertEquals(InferredType.LONG, meta.getProfiles().getFirst().getType());
        List<String> queries = new ArrayList<>();
        TargetColumnTypes.apply(connection(queries, Types.VARCHAR), new OracleDialect(), "KBE", "DEMO", meta, false);
        var db = new BatchingWriterTest.JdbcFixture();
        var writer = writer(db, meta);
        writer.write(List.of("00123"), 5002);
        writer.write(List.of("A123"), 5003);
        writer.flush();
        assertEquals(List.of("00123", "A123"), db.committed);
        assertEquals(0, writer.getFailedRows());
        assertEquals(List.of("SELECT \"CODE\" FROM \"KBE\".\"DEMO\" WHERE 1=0"), queries);
    }

    /** 全空采样不能让实际数值列继续使用 VARCHAR 的绑定方式。 */
    @Test public void existingNumericColumnOverridesEmptySample() throws Exception {
        CsvMeta meta = meta("VALUE");
        TargetColumnTypes.apply(connection(new ArrayList<>(), Types.NUMERIC), new OracleDialect(),
                "KBE", "DEMO", meta, false);
        var db = new BatchingWriterTest.JdbcFixture();
        writer(db, meta).write(List.of("00123.45"), 5002);
        assertEquals(new BigDecimal("123.45"), db.bound);
    }

    /** CSV 顺序决定参数顺序，各方言均使用实际写入目标的限定名称。 */
    @Test public void usesTargetNamesAndCsvColumnOrderForEveryDialect() throws Exception {
        for (DbType type : DbType.values()) {
            var dialect = DialectFactory.get(type);
            CsvMeta meta = meta("NOTE", "CREATED_AT", "CODE");
            List<String> queries = new ArrayList<>();
            TargetColumnTypes.apply(connection(queries, Types.CLOB, Types.DATE, Types.NVARCHAR),
                    dialect, "KBE", "DEMO", meta, false);
            assertEquals(List.of(InferredType.TEXT, InferredType.DATETIME, InferredType.STRING),
                    meta.getProfiles().stream().map(ColumnProfile::getType).toList());
            assertEquals("SELECT " + String.join(", ", meta.getColumns().stream()
                    .map(dialect::normalizeTableName).map(dialect::quote).toList())
                    + " FROM " + dialect.fullyQualified("KBE", "DEMO") + " WHERE 1=0", queries.getFirst());
        }
    }

    /** 新表仍按采样建成数值列时，保留原来的前导零保护。 */
    @Test public void sampledNewNumericColumnStillRejectsLeadingZeros() throws Exception {
        CsvMeta meta = meta("VALUE");
        meta.getProfiles().getFirst().setType(InferredType.LONG);
        TargetColumnTypes.apply(connection(new ArrayList<>(), Types.NUMERIC), new OracleDialect(),
                "KBE", "DEMO", meta, true);
        var db = new BatchingWriterTest.JdbcFixture();
        var writer = writer(db, meta);
        writer.write(List.of("00123"), 5002);
        assertEquals(1, writer.getFailedRows());
        assertTrue(db.batch.isEmpty());
    }

    /** 手工将新表列改为字符列后，绑定必须遵循实际类型。 */
    @Test public void editedNewTableUsesActualCharacterType() throws Exception {
        CsvMeta meta = meta("VALUE");
        meta.getProfiles().getFirst().setType(InferredType.LONG);
        TargetColumnTypes.apply(connection(new ArrayList<>(), Types.VARCHAR), new OracleDialect(),
                "KBE", "DEMO", meta, true);
        var db = new BatchingWriterTest.JdbcFixture();
        writer(db, meta).write(List.of("00123"), 5002);
        assertEquals("00123", db.bound);
    }

    /** 后续列无法绑定时完整报错，不能留下部分更新的类型。 */
    @Test public void unsupportedTypeFailsWithoutPartiallyChangingProfiles() {
        CsvMeta meta = meta("AMOUNT", "CONTENT");
        SQLException error = assertThrows(SQLException.class, () -> TargetColumnTypes.apply(
                connection(new ArrayList<>(), Types.NUMERIC, Types.BLOB), new OracleDialect(),
                "KBE", "DEMO", meta, false));
        assertTrue(error.getMessage().contains("DEMO.CONTENT"));
        assertEquals(InferredType.STRING, meta.getProfiles().getFirst().getType());
    }

    /** 元数据查询失败必须上抛，不退回不可靠的采样类型。 */
    @Test public void metadataQueryFailureIsPropagated() {
        SQLException failure = new SQLException("missing column", "42000");
        Statement statement = proxy(Statement.class, (method, args) -> {
            if (method.equals("executeQuery")) throw failure;
            return null;
        });
        Connection connection = proxy(Connection.class, (method, args) ->
                method.equals("createStatement") ? statement : null);
        assertSame(failure, assertThrows(SQLException.class, () -> TargetColumnTypes.apply(connection,
                new OracleDialect(), "KBE", "DEMO", meta("CODE"), false)));
    }

    /** 构造默认字符串采样。 */
    private static CsvMeta meta(String... columns) {
        return new CsvMeta(List.of(columns), java.util.Arrays.stream(columns).map(ColumnProfile::new).toList());
    }

    /** 使用项目现有写入器和事务替身验证实际绑定。 */
    private static BatchingWriter writer(BatchingWriterTest.JdbcFixture db, CsvMeta meta) {
        return new BatchingWriter(db.connection, db.statement, meta.getProfiles(), 1000,
                true, true, db.recorder, "DEMO", 0);
    }

    /** 只返回列元数据，不提供任何数据行。 */
    private static Connection connection(List<String> queries, int... types) {
        ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (method, args) -> switch (method) {
            case "getColumnCount" -> types.length;
            case "getColumnType" -> types[(int) args[0] - 1];
            case "getColumnTypeName" -> java.sql.JDBCType.valueOf(types[(int) args[0] - 1]).getName();
            default -> null;
        });
        ResultSet rows = proxy(ResultSet.class, (method, args) -> method.equals("getMetaData") ? metadata : null);
        Statement statement = proxy(Statement.class, (method, args) -> {
            if (method.equals("executeQuery")) {
                queries.add((String) args[0]);
                return rows;
            }
            return null;
        });
        return proxy(Connection.class, (method, args) -> method.equals("createStatement") ? statement : null);
    }
}
