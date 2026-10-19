package com.moral.task;

import com.moral.csv.CsvMetaReader;
import com.moral.csv.OracleTableDefinitions;
import com.moral.db.*;
import com.moral.model.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

import static com.moral.task.BatchingWriterTest.proxy;
import static org.junit.Assert.*;

/** 注释准备、混合结构变更及清表前失败隔离测试，使用 JDBC 替身。 */
public class SchemaCommentsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 五种数据库都只同步 CSV 使用列和表注释，重复导入无变化。 */
    @Test public void syncsCommentsAndSkipsUnchangedAcrossDialects() throws Exception {
        CsvTable table = csv();
        for (DbType type : DbType.values()) {
            Fixture db = new Fixture(type);
            SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
            SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(table), true,
                    new OracleTableDefinitions.Comments("分类", Map.of("CODE", "编码", "UNUSED", "不处理")), plan);
            assertEquals(2, plan.comments().size());
            SchemaComments.precheck(db.connection, db.dialect, plan);
            SchemaComments.apply(db.connection, db.dialect, plan, s -> {}, () -> false);
            assertEquals("分类", db.tableComment); assertEquals("编码", db.comment);
            SchemaPlan.Table retry = new SchemaPlan.Table("DEMO");
            SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(table), true,
                    new OracleTableDefinitions.Comments("分类", Map.of("CODE", "编码")), retry);
            assertTrue(retry.comments().isEmpty());
        }
    }

    /** 数据库注释存在换行或首尾空白时，内容相同不重复生成 COMMENT。 */
    @Test public void skipsEquivalentCommentWhitespace() throws Exception {
        Fixture db = new Fixture(DbType.ORACLE);
        db.comment = "进口药品注册证号\r\n";
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(csv()), true,
                new OracleTableDefinitions.Comments(null, Map.of("CODE", "进口药品注册证号")), plan);
        assertTrue(plan.comments().isEmpty());
    }

    /** MySQL 扩长与注释混合时，注释 SQL 必须使用扩长后的完整定义。 */
    @Test public void mysqlWidenAndCommentKeepAttributes() throws Exception {
        Fixture db = new Fixture(DbType.MYSQL);
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        var actual = CharacterColumns.read(db.connection, db.dialect, "KBE", "DEMO");
        SchemaPreparation.compare(db.dialect, actual, List.of("CODE"), Map.of("CODE", "VARCHAR2(500)"), plan, s -> {});
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(csv()), true,
                new OracleTableDefinitions.Comments(null, Map.of("CODE", "编码")), plan);
        String sql = plan.comments().getFirst().sql();
        assertTrue(sql.contains("varchar(500)")); assertTrue(sql.contains("DEFAULT 'seed'"));
        assertTrue(sql.contains("NOT NULL")); assertTrue(sql.contains("COLLATE utf8mb4_bin"));
        SchemaPreparation.applyChanges(db.connection, db.dialect, "KBE", "DEMO", plan, s -> {}, () -> false);
        assertEquals(500, db.length); assertEquals("编码", db.comment); assertEquals(2, db.executed.size());
    }

    /** MySQL 新表和新增列把注释放入原 DDL，不用不完整的 MODIFY 重述新列。 */
    @Test public void embedsMysqlNewTableAndAdditionComments() throws Exception {
        Fixture db = new Fixture(DbType.MYSQL);
        CsvTable table = csv();
        var meta = meta(table); meta.getProfiles().getFirst().setDeclaredSqlType("VARCHAR(100)");
        var source = new OracleTableDefinitions.Comments("分类", Map.of("CODE", "编码"));
        SchemaPlan.Table create = new SchemaPlan.Table("DEMO");
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta, false, source, create);
        assertTrue(create.createSql().contains("VARCHAR(100) COMMENT '编码'"));
        assertTrue(create.createSql().endsWith("COMMENT = '分类'"));
        assertTrue(create.comments().stream().allMatch(change -> change.sql() == null));
        SchemaPlan.Table add = new SchemaPlan.Table("DEMO");
        add.additions().add(new SchemaPlan.Addition("kbe", "demo", "code", "VARCHAR(100)",
                "ALTER TABLE `kbe`.`demo` ADD COLUMN `code` VARCHAR(100) NULL"));
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta, true,
                new OracleTableDefinitions.Comments(null, Map.of("CODE", "编码")), add);
        assertTrue(add.additions().getFirst().sql().endsWith("NULL COMMENT '编码'"));
        assertNull(add.comments().getFirst().sql());
    }

    /** Oracle 新表使用独立、限定 Schema 的 COMMENT ON 语句。 */
    @Test public void nativeNewTableCommentsAreSeparateStatements() throws Exception {
        Fixture db = new Fixture(DbType.ORACLE);
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(csv()), false,
                new OracleTableDefinitions.Comments("分类", Map.of("CODE", "编码")), plan);
        assertEquals("COMMENT ON TABLE \"KBE\".\"DEMO\" IS '分类'", plan.comments().get(0).sql());
        assertEquals("COMMENT ON COLUMN \"KBE\".\"DEMO\".\"CODE\" IS '编码'", plan.comments().get(1).sql());
    }

    /** 真实引擎中注释提交验证在清表之前，失败不能误清表。 */
    @Test public void commentFailuresNeverReachClear() throws Exception {
        CsvTable table = csv();
        for (int mode = 0; mode < 4; mode++) {
            Fixture db = new Fixture(DbType.POSTGRESQL);
            SchemaPlan plan = new SchemaPlan(); SchemaPlan.Table changes = new SchemaPlan.Table("DEMO");
            plan.tables().put("DEMO", changes);
            SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(table), true,
                    new OracleTableDefinitions.Comments(null, Map.of("CODE", "编码")), changes);
            db.failDdl = mode == 0; db.failCommit = mode == 1; db.ignoreDdl = mode == 2;
            if (mode == 3) db.comment = "他人修改";
            assertThrows(Exception.class, () -> runPrepare(db, table, plan));
            assertTrue(db.executed.stream().noneMatch(sql -> sql.startsWith("TRUNCATE") || sql.startsWith("DELETE")));
        }
        Fixture db = new Fixture(DbType.POSTGRESQL);
        SchemaPlan plan = new SchemaPlan(); SchemaPlan.Table changes = new SchemaPlan.Table("DEMO");
        plan.tables().put("DEMO", changes);
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(table), true,
                new OracleTableDefinitions.Comments(null, Map.of("CODE", "编码")), changes);
        runPrepare(db, table, plan);
        assertTrue(db.executed.get(0).startsWith("COMMENT ON"));
        assertTrue(db.executed.get(1).startsWith("TRUNCATE"));
    }

    /** 明确空注释会清空，未声明注释不生成变更。 */
    @Test public void clearsOnlyExplicitEmptyComments() throws Exception {
        Fixture db = new Fixture(DbType.ORACLE); db.comment = "旧注释"; db.tableComment = "保留";
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(csv()), true,
                new OracleTableDefinitions.Comments(null, Map.of("CODE", "")), plan);
        assertEquals(1, plan.comments().size());
        SchemaComments.apply(db.connection, db.dialect, plan, s -> {}, () -> false);
        assertEquals("", db.comment); assertEquals("保留", db.tableComment);
    }

    /** 取消、会话模式变化和列属性变化均阻止 MySQL 注释修改。 */
    @Test public void rejectsMysqlSessionAndAttributeChanges() throws Exception {
        Fixture db = new Fixture(DbType.MYSQL);
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(csv()), true,
                new OracleTableDefinitions.Comments(null, Map.of("CODE", "编码")), plan);
        assertThrows(SQLException.class, () -> SchemaComments.apply(db.connection, db.dialect, plan, s -> {}, () -> true));
        db.noBackslash = true;
        assertThrows(SQLException.class, () -> SchemaComments.precheck(db.connection, db.dialect, plan));
        db.noBackslash = false; db.columnSuffix = " NOT NULL DEFAULT 'changed'";
        assertThrows(SQLException.class, () -> SchemaComments.apply(db.connection, db.dialect, plan, s -> {}, () -> false));
        assertTrue(db.executed.isEmpty());
    }

    /** 执行连接指向其他 MySQL 数据库时不能写入旧目标后清空新目标。 */
    @Test public void rejectsChangedMysqlCatalog() throws Exception {
        Fixture db = new Fixture(DbType.MYSQL);
        SchemaPlan.Table plan = new SchemaPlan.Table("DEMO");
        SchemaComments.prepare(db.connection, db.dialect, "KBE", "DEMO", meta(csv()), true,
                new OracleTableDefinitions.Comments("分类", Map.of()), plan);
        db.catalog = "another_database";
        SQLException error = assertThrows(SQLException.class, () -> SchemaComments.precheck(db.connection, db.dialect, plan));
        assertTrue(error.getMessage().contains("目标数据库"));
        assertTrue(db.executed.isEmpty());
    }

    /** 准备入口读取真实源脚本，只有注释选项也会产生预览。 */
    @Test public void preparationIntegratesCommentOnlyOption() throws Exception {
        Fixture db = new Fixture(DbType.ORACLE);
        CsvTable table = csv();
        Files.writeString(folder.getRoot().toPath().resolve("_tables.sql"),
                "COMMENT ON TABLE KBE.DEMO IS '分类'; COMMENT ON COLUMN KBE.DEMO.CODE IS '编码';");
        ImportOptions options = new ImportOptions(); options.setSyncComments(true);
        SchemaPlan plan = SchemaPreparation.prepare(db.connection, db.dialect, new ConnectionConfig(), options, List.of(table), s -> {});
        assertTrue(plan.hasChanges()); assertEquals(0, plan.failedCount()); assertEquals(2, plan.tables().get("DEMO").comments().size());
        options.setSyncComments(false);
        assertFalse(SchemaPreparation.prepare(db.connection, db.dialect, new ConnectionConfig(), options, List.of(table), s -> {}).hasChanges());
    }

    /** 最小 CSV，保证所有检查走项目解析器。 */
    private CsvTable csv() throws Exception {
        var path = folder.getRoot().toPath().resolve("DEMO.csv"); Files.writeString(path, "CODE\nx\n");
        return new CsvTable(path.toFile());
    }

    /** 使用真实 CSV 元数据。 */
    private com.moral.csv.CsvMeta meta(CsvTable table) throws Exception { return CsvMetaReader.read(table.getFile(), StandardCharsets.UTF_8); }

    /** 调用引擎实际清表边界。 */
    private void runPrepare(Fixture db, CsvTable table, SchemaPlan plan) throws Exception {
        var method = ImportEngine.class.getDeclaredMethod("prepareTable", Connection.class, Dialect.class, ConnectionConfig.class,
                ImportOptions.class, CsvTable.class, SchemaPlan.class, OracleTableDefinitions.class, ProgressListener.class);
        method.setAccessible(true);
        ImportOptions options = new ImportOptions(); options.setSyncComments(true); options.setClearMode(ImportOptions.ClearMode.TRUNCATE);
        ProgressListener listener = new ProgressListener() {
            public void onLog(String text) { }
            public void onTick() { }
            public void onTableFinished(CsvTable value) { }
            public void onAllFinished(ImportResult result) { }
        };
        try { method.invoke(new ImportEngine(), db.connection, db.dialect, new ConnectionConfig(), options, table, plan,
                new OracleTableDefinitions(), listener); }
        catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
    }

    /** 按查询返回五种方言的目录和注释，记录 DDL 与清表。 */
    private static final class Fixture {
        final DbType type;
        final Dialect dialect;
        final Connection connection;
        String catalog = "kbe";
        String comment = "", tableComment = "", columnSuffix = " NOT NULL DEFAULT 'seed'";
        int length = 100;
        boolean failDdl, failCommit, ignoreDdl, noBackslash;
        final List<String> executed = new ArrayList<>();

        Fixture(DbType type) {
            this.type = type;
            Dialect actual = DialectFactory.get(type);
            dialect = (Dialect) Proxy.newProxyInstance(Dialect.class.getClassLoader(), new Class<?>[]{Dialect.class},
                    (object, method, args) -> method.getName().equals("tableExists") ? true : method.invoke(actual, args));
            connection = proxy(Connection.class, (method, args) -> {
                if (method.equals("getCatalog")) return catalog;
                if (method.equals("getSchema")) return "KBE";
                if (method.equals("commit") && failCommit) throw new SQLException("commit failed");
                if (method.equals("prepareStatement")) {
                    String sql = (String) args[0];
                    return proxy(PreparedStatement.class, (op, values) -> op.equals("executeQuery") ? query(sql) : null);
                }
                if (method.equals("createStatement")) return proxy(Statement.class, (op, values) -> {
                    if (op.equals("executeQuery")) {
                        String sql = (String) values[0];
                        if (sql.startsWith("SHOW CREATE")) return rows(List.<Object[]>of(new Object[]{"demo", "CREATE TABLE `demo` (" + definition() + ")"}));
                        if (sql.startsWith("SELECT @@")) return rows(List.<Object[]>of(new Object[]{noBackslash ? "NO_BACKSLASH_ESCAPES" : ""}));
                        var metadata = proxy(ResultSetMetaData.class, (read, params) -> switch (read) {
                            case "getColumnCount" -> 1;
                            case "getColumnType" -> Types.VARCHAR;
                            case "getColumnTypeName" -> "VARCHAR";
                            default -> null;
                        });
                        return proxy(ResultSet.class, (read, params) -> read.equals("getMetaData") ? metadata : null);
                    }
                    if (op.equals("execute")) {
                        String sql = (String) values[0]; executed.add(sql);
                        if (failDdl) throw new SQLException("DDL failed");
                        if (!ignoreDdl) {
                            if (sql.contains("MODIFY COLUMN")) {
                                var size = java.util.regex.Pattern.compile("varchar\\((\\d+)\\)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
                                if (size.find()) length = Integer.parseInt(size.group(1));
                            }
                            int index = sql.lastIndexOf(" COMMENT '");
                            if (index >= 0) comment = sql.substring(index + 10, sql.length() - 1);
                            else if (sql.contains(" IS ") || sql.contains(" COMMENT = ")) {
                                String text = sql.substring(sql.indexOf('\'') + 1, sql.lastIndexOf('\'')).replace("''", "'");
                                if (sql.startsWith("COMMENT ON COLUMN")) comment = text;
                                else tableComment = text;
                            }
                        }
                        return false;
                    }
                    return null;
                });
                return null;
            });
        }

        /** 当前完整 MySQL 列定义。 */
        String definition() { return "`code` varchar(" + length + ") CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" + columnSuffix
                + (comment.isEmpty() ? "" : " COMMENT '" + comment + "'"); }

        /** 区分列目录、表注释和列注释查询。 */
        ResultSet query(String sql) {
            String name = dialect.normalizeTableName("CODE");
            if (sql.contains("ALL_COL_COMMENTS") || sql.contains("COLUMN_COMMENT") || sql.contains("col_description"))
                return rows(List.<Object[]>of(new Object[]{name, comment}));
            if (sql.contains("ALL_TAB_COMMENTS") || sql.contains("TABLE_COMMENT") || sql.contains("obj_description"))
                return rows(List.<Object[]>of(new Object[]{tableComment}));
            if (type == DbType.ORACLE || type == DbType.DAMENG)
                return rows(List.<Object[]>of(new Object[]{name, "VARCHAR2", (long) length, (long) length, "C"}));
            return rows(List.<Object[]>of(new Object[]{name, "varchar", (long) length,
                    type == DbType.MYSQL ? "utf8mb4" : null, "utf8mb4_bin"}));
        }

        /** 最小目录结果集。 */
        ResultSet rows(List<Object[]> data) {
            int[] cursor = {-1};
            return proxy(ResultSet.class, (op, args) -> switch (op) {
                case "next" -> ++cursor[0] < data.size();
                case "getString", "getLong" -> data.get(cursor[0])[(int) args[0] - 1];
                case "wasNull" -> false;
                default -> null;
            });
        }
    }
}
