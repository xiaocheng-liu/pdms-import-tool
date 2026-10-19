package com.moral.task;

import com.moral.csv.OracleTableDefinitions;
import com.moral.db.CharacterColumns;
import com.moral.db.Dialect;
import com.moral.db.OracleDialect;
import com.moral.model.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static com.moral.task.BatchingWriterTest.proxy;
import static org.junit.Assert.*;

/** 结构预检、执行顺序和失败恢复测试，所有数据库调用使用内存替身。 */
public class SchemaPreparationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 比较只生成同类型扩长，长度减少和缺少源列仅提示。 */
    @Test public void comparesOnlySelectedColumnsAndOnlyWidens() throws Exception {
        Fixture db = new Fixture();
        db.lengths.put("EXTRA", 100);
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        List<String> notes = new ArrayList<>();
        SchemaPreparation.compare(db.dialect, db.metadata(), List.of("CODE", "NOTE"),
                Map.of("CODE", "VARCHAR2(500)", "NOTE", "VARCHAR2(50)", "EXTRA", "VARCHAR2(999)"), plan, notes::add);
        assertEquals(1, plan.widenings().size());
        assertEquals("CODE", plan.widenings().getFirst().before().name());
        assertEquals(500, plan.widenings().getFirst().length());
        assertTrue(notes.getFirst().contains("不缩短"));
    }

    /** 类型、单位、大字段转换均不能生成 ALTER。 */
    @Test public void excludesTypeAndUnitChanges() throws Exception {
        for (String declaration : List.of("VARCHAR2(500 BYTE)", "CHAR(500)", "CLOB", "NUMBER(20)", "VARCHAR2(100)")) {
            Fixture db = new Fixture();
            SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
            SchemaPreparation.compare(db.dialect, db.metadata(), List.of("CODE"), Map.of("CODE", declaration), plan, s -> {});
            assertTrue(declaration, plan.widenings().isEmpty());
        }
    }

    /** 源与目标同为 NCLOB 时不提示类型差异。 */
    @Test public void skipsSameLargeTextType() throws Exception {
        Fixture db = new Fixture();
        db.type = "NCLOB";
        db.lengths.put("CODE", -1);
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        List<String> notes = new ArrayList<>();
        SchemaPreparation.compare(db.dialect, db.metadata(), List.of("CODE"),
                Map.of("CODE", "NCLOB"), plan, notes::add);
        assertTrue(plan.widenings().isEmpty());
        assertTrue(notes.isEmpty());
    }

    /** 源数值声明带精度时，目录基础类型 NUMBER 不应提示类型差异。 */
    @Test public void skipsSameNumericTypeFamily() throws Exception {
        Fixture db = new Fixture();
        db.type = "NUMBER";
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        List<String> notes = new ArrayList<>();
        SchemaPreparation.compare(db.dialect, db.metadata(), List.of("CODE"),
                Map.of("CODE", "NUMBER(10,0)"), plan, notes::add);
        assertTrue(plan.widenings().isEmpty());
        assertTrue(notes.isEmpty());
    }

    /** 脚本未指定单位时沿用数据库的 BYTE；缺少源列不扩长。 */
    @Test public void preservesUnspecifiedUnitAndSkipsMissingSourceColumn() throws Exception {
        Fixture db = new Fixture();
        db.unit = "BYTE";
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        SchemaPreparation.compare(db.dialect, db.metadata(), List.of("CODE", "NOTE"),
                Map.of("CODE", "VARCHAR2(500)"), plan, plan.notes()::add);
        assertTrue(plan.widenings().getFirst().sql().contains("500 BYTE"));
        assertEquals(1, plan.notes().size());
    }

    /** 新建表与已有表扩长可以一次预览，预检不执行任何 DDL。 */
    @Test public void preparesMixedCreateAndWidenPlan() throws Exception {
        Fixture db = new Fixture();
        CsvTable demo = csv("DEMO");
        CsvTable missing = csv("NEW_TABLE");
        source("CREATE TABLE DEMO(CODE VARCHAR2(500)); CREATE TABLE NEW_TABLE(CODE VARCHAR2(200));");
        ImportOptions options = options();
        options.setAutoCreateTable(true);
        SchemaPlan plan = SchemaPreparation.prepare(db.connection, db.dialect, config(), options,
                List.of(demo, missing), s -> {});
        assertTrue(plan.hasChanges());
        assertEquals(0, plan.failedCount());
        assertEquals(1, plan.tables().get("DEMO").widenings().size());
        assertTrue(plan.tables().get("NEW_TABLE").createSql().contains("VARCHAR2(200 CHAR)"));
        assertTrue(db.executed.isEmpty());
    }

    /** 没有脚本时不使用 CSV 采样扩长。 */
    @Test public void missingSourceDoesNotWiden() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan plan = SchemaPreparation.prepare(db.connection, db.dialect, config(), options(), List.of(csv("DEMO")), s -> {});
        assertFalse(plan.hasChanges());
        assertEquals(0, plan.failedCount());
        assertTrue(db.executed.isEmpty());
    }

    /** 重复定义和损坏脚本要成为明确失败表，不能当成无需变更。 */
    @Test public void invalidSourceMarksTableFailed() throws Exception {
        CsvTable table = csv("DEMO");
        for (String sql : List.of("CREATE TABLE DEMO(CODE VARCHAR2(500)",
                "CREATE TABLE DEMO(CODE VARCHAR2(500)); CREATE TABLE DEMO(CODE VARCHAR2(100));")) {
            Fixture db = new Fixture();
            source(sql);
            SchemaPlan plan = SchemaPreparation.prepare(db.connection, db.dialect, config(), options(), List.of(table), s -> {});
            assertEquals(1, plan.failedCount());
            assertFalse(plan.hasChanges());
            assertTrue(db.executed.isEmpty());
        }
    }

    /** 单表元数据失败不能阻止其他表生成建表计划。 */
    @Test public void metadataFailureDoesNotHideOtherTables() throws Exception {
        Fixture db = new Fixture();
        db.metadataFailure = true;
        source("CREATE TABLE DEMO(CODE VARCHAR2(500)); CREATE TABLE NEW_TABLE(CODE VARCHAR2(200));");
        ImportOptions options = options(); options.setAutoCreateTable(true);
        SchemaPlan plan = SchemaPreparation.prepare(db.connection, db.dialect, config(), options,
                List.of(csv("DEMO"), csv("NEW_TABLE")), s -> {});
        assertEquals(1, plan.failedCount());
        assertTrue(plan.hasChanges());
        assertNotNull(plan.tables().get("NEW_TABLE").createSql());
    }

    /** 扩长验证成功之后才清表；再次执行已经达标的列不重复 ALTER。 */
    @Test public void engineWidensBeforeClearAndRetrySkipsAlter() throws Exception {
        Fixture db = new Fixture();
        CsvTable table = csv("DEMO");
        SchemaPlan plan = plan(db, "CODE");
        prepareTable(db, table, plan);
        assertEquals(500, (int) db.lengths.get("CODE"));
        assertTrue(db.executed.get(0).startsWith("ALTER TABLE"));
        assertTrue(db.executed.get(1).startsWith("TRUNCATE TABLE"));
        db.executed.clear();
        prepareTable(db, table, plan);
        assertEquals(1, db.executed.size());
        assertTrue(db.executed.getFirst().startsWith("TRUNCATE TABLE"));
    }

    /** DDL、提交及结果验证失败均不能执行清表。 */
    @Test public void engineNeverClearsAfterAlterCommitOrVerificationFailure() throws Exception {
        CsvTable table = csv("DEMO");
        for (int mode = 0; mode < 3; mode++) {
            Fixture db = new Fixture();
            SchemaPlan plan = plan(db, "CODE");
            db.failAlter = mode == 0 ? 1 : 0;
            db.commitFailure = mode == 1;
            db.ignoreAlter = mode == 2;
            assertThrows(Exception.class, () -> prepareTable(db, table, plan));
            assertTrue(db.executed.stream().noneMatch(sql -> sql.startsWith("TRUNCATE") || sql.startsWith("DELETE")));
        }
    }

    /** 已失败的预检结果不能到达数据库清表步骤。 */
    @Test public void engineStopsPrecheckFailureBeforeAnySql() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan plan = plan(db, "CODE");
        plan.tables().get("DEMO").setError("元数据读取失败");
        assertThrows(Exception.class, () -> prepareTable(db, csv("DEMO"), plan));
        assertTrue(db.executed.isEmpty());
        assertEquals(0, db.reads);
    }

    /** 预览后类型、单位或长度减少时拒绝旧计划，并先核对所有列。 */
    @Test public void changedDefinitionStopsBeforeAnyAlter() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Fixture db = new Fixture();
            SchemaPlan plan = plan(db, "CODE", "NOTE");
            if (mode == 0) db.type = "CHAR";
            if (mode == 1) db.unit = "BYTE";
            if (mode == 2) db.lengths.put("NOTE", 50);
            assertThrows(SQLException.class, () -> SchemaPreparation.applyChanges(db.connection, db.dialect,
                    "KBE", "DEMO", plan.tables().get("DEMO"), s -> {}, () -> false));
            assertTrue(db.executed.isEmpty());
        }
    }

    /** 部分 ALTER 已完成时日志保留进度，重试只改未完成列。 */
    @Test public void retriesOnlyRemainingColumnsAfterPartialFailure() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan plan = plan(db, "CODE", "NOTE");
        db.failAlter = 2;
        List<String> logs = new ArrayList<>();
        SQLException error = assertThrows(SQLException.class, () -> SchemaPreparation.applyChanges(db.connection,
                db.dialect, "KBE", "DEMO", plan.tables().get("DEMO"), logs::add, () -> false));
        assertTrue(error.getMessage().contains("已确认完成 1 项"));
        assertTrue(logs.stream().anyMatch(s -> s.contains("[已扩长]")));
        db.failAlter = 0; db.executed.clear();
        SchemaPreparation.applyChanges(db.connection, db.dialect, "KBE", "DEMO", plan.tables().get("DEMO"), logs::add, () -> false);
        assertEquals(1, db.executed.size());
        assertTrue(db.executed.getFirst().contains("\"NOTE\""));
    }

    /** 取消后不启动扩长语句。 */
    @Test public void cancellationPreventsAlter() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan plan = plan(db, "CODE");
        assertThrows(SQLException.class, () -> SchemaPreparation.applyChanges(db.connection, db.dialect,
                "KBE", "DEMO", plan.tables().get("DEMO"), s -> {}, () -> true));
        assertTrue(db.executed.isEmpty());
    }

    /** 五种方言仅给 CSV 缺失列生成单条 ADD，已有列不随新增开关扩长。 */
    @Test public void additionsUseMappedTypesAcrossDialects() throws Exception {
        for (DbType type : DbType.values()) {
            Dialect dialect = com.moral.db.DialectFactory.get(type);
            String existing = dialect.normalizeTableName("CODE");
            var target = new CharacterColumns.Table("KBE", "DEMO", Map.of(existing,
                    new CharacterColumns.Column(existing, "VARCHAR", 100, "CHAR", "")));
            SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
            SchemaPreparation.compare(dialect, target, List.of("CODE", "NEW_COL"),
                    Map.of("CODE", "VARCHAR2(500)", "NEW_COL", "NUMBER(12,2)", "UNUSED", "DATE"),
                    plan, plan.notes()::add, false, true);
            assertTrue(plan.widenings().isEmpty());
            assertEquals(1, plan.additions().size());
            String sql = plan.additions().getFirst().sql();
            assertTrue(sql.contains(CharacterColumns.qualified(dialect, "KBE", "DEMO")));
            assertTrue(sql.contains(dialect.quote(dialect.normalizeTableName("NEW_COL"))));
            assertTrue(sql.contains(type == DbType.ORACLE || type == DbType.DAMENG ? " ADD (" : " ADD COLUMN "));
            assertTrue(sql.contains("(12,2) NULL"));
            assertFalse(sql.contains("UNUSED"));
        }
    }

    /** 新增字段名中的引用符必须转义，不能改变已预览语句的结构。 */
    @Test public void escapesAddedColumnIdentifiers() throws Exception {
        for (DbType type : DbType.values()) {
            Dialect dialect = com.moral.db.DialectFactory.get(type);
            String name = type == DbType.MYSQL ? "NEW`COL" : "NEW\"COL";
            SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
            SchemaPreparation.compare(dialect, new CharacterColumns.Table("KBE", "DEMO", Map.of()),
                    List.of(name), Map.of(name, "VARCHAR2(100)"), plan, s -> {}, false, true);
            assertTrue(plan.additions().getFirst().sql().contains(type == DbType.MYSQL
                    ? "`new``col`" : type == DbType.ORACLE || type == DbType.DAMENG
                    ? "\"NEW\"\"COL\"" : "\"new\"\"col\""));
        }
    }

    /** 新增关闭、无源定义或不支持类型时禁止猜测列类型。 */
    @Test public void additionsRequireEnabledOptionAndReliableSource() throws Exception {
        Fixture db = new Fixture();
        for (String declaration : List.of("", "BLOB", "VARCHAR2(500)")) {
            Map<String, String> source = declaration.isEmpty() ? Map.of() : Map.of("NEW_COL", declaration);
            assertThrows(SQLException.class, () -> SchemaPreparation.compare(db.dialect, db.metadata(),
                    List.of("NEW_COL"), source, new SchemaPlan.Table("DEMO"), s -> {}, false,
                    !declaration.equals("VARCHAR2(500)")));
        }
    }

    /** 真实脚本的默认值和非空约束不复制，新增、扩长、建表可混合预览。 */
    @Test public void preparesNullableAdditionsWithMixedChanges() throws Exception {
        Fixture db = new Fixture();
        CsvTable table = csv("DEMO");
        Files.writeString(table.getFile().toPath(), "CODE,NEW_COL\nabc,def\n");
        source("CREATE TABLE DEMO(CODE VARCHAR2(500), NEW_COL VARCHAR2(80) DEFAULT 'x' NOT NULL);"
                + "CREATE TABLE NEW_TABLE(CODE VARCHAR2(200));");
        ImportOptions options = options(); options.setAddMissingColumns(true); options.setAutoCreateTable(true);
        SchemaPlan plan = SchemaPreparation.prepare(db.connection, db.dialect, config(), options,
                List.of(table, csv("NEW_TABLE")), s -> {});
        assertEquals(0, plan.failedCount());
        assertTrue(plan.hasChanges());
        assertNotNull(plan.tables().get("NEW_TABLE").createSql());
        SchemaPlan.Table changes = plan.tables().get("DEMO");
        assertEquals(1, changes.widenings().size());
        assertEquals(1, changes.additions().size());
        assertTrue(changes.additions().getFirst().sql().contains("VARCHAR2(80 CHAR) NULL"));
        assertFalse(changes.additions().getFirst().sql().contains("DEFAULT"));
        assertTrue(changes.notes().stream().anyMatch(note -> note.contains("非空")));
        assertTrue(db.executed.isEmpty());
    }

    /** 新增可独立启用，验证成功才清表，再次准备不会重复新增。 */
    @Test public void engineAddsBeforeClearAndRetryNeedsNoAddition() throws Exception {
        Fixture db = new Fixture(); db.lengths.remove("CODE");
        CsvTable table = csv("DEMO");
        source("CREATE TABLE DEMO(CODE VARCHAR2(500));");
        ImportOptions options = new ImportOptions(); options.setAddMissingColumns(true);
        options.setClearMode(ImportOptions.ClearMode.TRUNCATE);
        SchemaPlan plan = SchemaPreparation.prepare(db.connection, db.dialect, config(), options, List.of(table), s -> {});
        prepareTable(db, table, plan, options);
        assertEquals(500, (int) db.lengths.get("CODE"));
        assertTrue(db.executed.get(0).contains(" ADD ("));
        assertTrue(db.executed.get(1).startsWith("TRUNCATE"));
        SchemaPlan retry = SchemaPreparation.prepare(db.connection, db.dialect, config(), options, List.of(table), s -> {});
        assertFalse(retry.hasChanges());
        db.executed.clear(); prepareTable(db, table, retry, options);
        assertEquals(1, db.executed.size());
        assertTrue(db.executed.getFirst().startsWith("TRUNCATE"));
    }

    /** 新增 DDL、提交、长度验证和可空性验证失败均不能清表。 */
    @Test public void additionFailuresNeverClear() throws Exception {
        CsvTable table = csv("DEMO");
        source("CREATE TABLE DEMO(CODE VARCHAR2(500));");
        for (int mode = 0; mode < 5; mode++) {
            Fixture db = new Fixture(); db.lengths.remove("CODE");
            ImportOptions options = options(); options.setAddMissingColumns(true);
            SchemaPlan plan = SchemaPreparation.prepare(db.connection, db.dialect, config(), options, List.of(table), s -> {});
            db.failAlter = mode == 0 ? 1 : 0;
            db.commitFailure = mode == 1; db.ignoreAlter = mode == 2;
            db.wrongAddedLength = mode == 3; db.notNullable = mode == 4;
            assertThrows(Exception.class, () -> prepareTable(db, table, plan, options));
            assertTrue(db.executed.stream().noneMatch(sql -> sql.startsWith("TRUNCATE") || sql.startsWith("DELETE")));
        }
    }

    /** 预览后缺失列出现，即使类型相同，也先停止所有新增与扩长，要求重新预览。 */
    @Test public void concurrentlyAddedColumnStopsAllChanges() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        SchemaPreparation.compare(db.dialect, db.metadata(), List.of("CODE", "NEW_COL"),
                Map.of("CODE", "VARCHAR2(500)", "NEW_COL", "VARCHAR2(100)"), plan, s -> {}, true, true);
        db.lengths.put("NEW_COL", 100);
        assertThrows(SQLException.class, () -> SchemaPreparation.applyChanges(db.connection, db.dialect,
                "KBE", "DEMO", plan, s -> {}, () -> false));
        assertTrue(db.executed.isEmpty());
    }

    /** 多列新增中途失败，重新准备仅保留尚未添加的列；取消不执行 DDL。 */
    @Test public void retriesRemainingAdditionsAndHonorsCancellation() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        List<String> columns = List.of("NEW_A", "NEW_B");
        Map<String, String> source = Map.of("NEW_A", "VARCHAR2(100)", "NEW_B", "VARCHAR2(100)");
        SchemaPreparation.compare(db.dialect, db.metadata(), columns, source, plan, s -> {}, false, true);
        assertThrows(SQLException.class, () -> SchemaPreparation.applyChanges(db.connection, db.dialect,
                "KBE", "DEMO", plan, s -> {}, () -> true));
        assertTrue(db.executed.isEmpty());
        db.failAlter = 2;
        SQLException failure = assertThrows(SQLException.class, () -> SchemaPreparation.applyChanges(db.connection,
                db.dialect, "KBE", "DEMO", plan, s -> {}, () -> false));
        assertTrue(failure.getMessage().contains("已确认完成 1 项"));
        SchemaPlan.Table retry = new SchemaPlan.Table("DEMO");
        SchemaPreparation.compare(db.dialect, db.metadata(), columns, source, retry, s -> {}, false, true);
        assertEquals(1, retry.additions().size());
        assertEquals("NEW_B", retry.additions().getFirst().column());
        db.failAlter = 0; db.executed.clear();
        SchemaPreparation.applyChanges(db.connection, db.dialect, "KBE", "DEMO", retry, s -> {}, () -> false);
        assertEquals(1, db.executed.size());
    }

    /** 当前选项独立于自动建表。 */
    private static ImportOptions options() {
        ImportOptions options = new ImportOptions(); options.setSyncColumnLengths(true);
        options.setClearMode(ImportOptions.ClearMode.TRUNCATE);
        return options;
    }

    /** 固定测试 Schema，确保所有 SQL 限定目标。 */
    private static ConnectionConfig config() { return new ConnectionConfig(); }

    /** 生成已有表扩长的不可编辑 SQL。 */
    private static SchemaPlan plan(Fixture db, String... names) throws SQLException {
        SchemaPlan plan = new SchemaPlan(); SchemaPlan.Table table = new SchemaPlan.Table("DEMO");
        Map<String, String> source = new LinkedHashMap<>();
        for (String name : names) source.put(name, "VARCHAR2(500 CHAR)");
        SchemaPreparation.compare(db.dialect, db.metadata(), List.of(names), source, table, s -> {});
        plan.tables().put("DEMO", table);
        return plan;
    }

    /** 执行引擎实际准备入口，验证错误不能越过清表边界。 */
    private static void prepareTable(Fixture db, CsvTable table, SchemaPlan plan) throws Exception {
        prepareTable(db, table, plan, options());
    }

    /** 使用指定开关测试实际引擎准备流程。 */
    private static void prepareTable(Fixture db, CsvTable table, SchemaPlan plan, ImportOptions options) throws Exception {
        var method = ImportEngine.class.getDeclaredMethod("prepareTable", Connection.class, Dialect.class,
                ConnectionConfig.class, ImportOptions.class, CsvTable.class, SchemaPlan.class,
                OracleTableDefinitions.class, ProgressListener.class);
        method.setAccessible(true);
        ProgressListener listener = new ProgressListener() {
            public void onLog(String text) { }
            public void onTick() { }
            public void onTableFinished(CsvTable value) { }
            public void onAllFinished(ImportResult result) { }
        };
        try {
            method.invoke(new ImportEngine(), db.connection, db.dialect, config(), options, table, plan,
                    new OracleTableDefinitions(), listener);
        } catch (InvocationTargetException error) {
            throw (Exception) error.getCause();
        }
    }

    /** 写入最小合法 CSV。 */
    private CsvTable csv(String name) throws Exception {
        var path = folder.getRoot().toPath().resolve(name + ".csv");
        Files.writeString(path, "CODE\n00123\n");
        return new CsvTable(path.toFile());
    }

    /** 使用真实脚本解析，数据库目录由替身提供。 */
    private void source(String text) throws Exception { Files.writeString(folder.getRoot().toPath().resolve("_tables.sql"), text); }

    /** 可注入 DDL、提交和元数据故障的 Oracle 目录替身。 */
    private static final class Fixture {
        final Map<String, Integer> lengths = new LinkedHashMap<>(Map.of("CODE", 100, "NOTE", 100));
        final List<String> executed = new ArrayList<>();
        String type = "VARCHAR2";
        String unit = "CHAR";
        int failAlter, alters, reads;
        boolean commitFailure, metadataFailure, ignoreAlter, wrongAddedLength, notNullable;
        final Dialect dialect = new OracleDialect() {
            @Override public boolean tableExists(Connection conn, String schema, String table) { return !table.equals("NEW_TABLE"); }
        };

        final Connection connection = proxy(Connection.class, (method, args) -> {
            if (method.equals("prepareStatement")) return proxy(PreparedStatement.class, (name, params) -> {
                if (!name.equals("executeQuery")) return null;
                reads++;
                if (metadataFailure) throw new SQLException("metadata denied");
                var entries = new ArrayList<>(lengths.entrySet());
                int[] index = {-1};
                return proxy(ResultSet.class, (operation, values) -> switch (operation) {
                    case "next" -> ++index[0] < entries.size();
                    case "wasNull" -> false;
                    case "getLong" -> (long) entries.get(index[0]).getValue();
                    case "getString" -> switch ((int) values[0]) {
                        case 1 -> entries.get(index[0]).getKey();
                        case 2 -> type;
                        case 5 -> unit.equals("CHAR") ? "C" : "B";
                        default -> null;
                    };
                    default -> null;
                });
            });
            if (method.equals("createStatement")) return proxy(Statement.class, (name, params) -> {
                if (name.equals("executeQuery")) {
                    var metadata = proxy(ResultSetMetaData.class, (op, values) -> switch (op) {
                        case "getColumnCount" -> 1;
                        case "isNullable" -> notNullable ? ResultSetMetaData.columnNoNulls : ResultSetMetaData.columnNullable;
                        case "getColumnType" -> Types.VARCHAR;
                        case "getColumnTypeName" -> "VARCHAR2";
                        default -> null;
                    });
                    return proxy(ResultSet.class, (op, values) -> op.equals("getMetaData") ? metadata : null);
                }
                if (name.equals("execute")) {
                    String sql = (String) params[0]; executed.add(sql);
                    if (sql.startsWith("ALTER")) {
                        if (++alters == failAlter) throw new SQLException("alter denied");
                        var matcher = Pattern.compile("(?:MODIFY|ADD) \\(\"([^\"]+)\" \\w+\\((\\d+)").matcher(sql);
                        if (!ignoreAlter && matcher.find()) lengths.put(matcher.group(1), wrongAddedLength ? 1 : Integer.parseInt(matcher.group(2)));
                    }
                    return false;
                }
                return null;
            });
            if (method.equals("commit") && commitFailure) throw new SQLException("commit lost");
            return null;
        });

        /** 比较使用的实际列快照。 */
        CharacterColumns.Table metadata() {
            Map<String, CharacterColumns.Column> columns = new LinkedHashMap<>();
            lengths.forEach((name, length) -> columns.put(name, new CharacterColumns.Column(name, type, length, unit, "")));
            return new CharacterColumns.Table("KBE", "DEMO", columns);
        }
    }
}
