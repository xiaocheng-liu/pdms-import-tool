package com.moral.task;

import com.moral.db.CharacterColumns;
import com.moral.db.MySQLDialect;
import com.moral.model.SchemaPlan;
import org.junit.Test;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.moral.task.BatchingWriterTest.proxy;
import static org.junit.Assert.*;

/** MySQL 扩长后 SHOW CREATE 规范化和属性变化的流程回归测试。 */
public class MySqlSchemaChangesTest {
    /** 原定义省略字符集时，扩长后补出字符集仍应验证成功，重试跳过。 */
    @Test public void acceptsServerNormalizationAfterAlter() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan.Table plan = db.plan();
        assertTrue(plan.widenings().getFirst().sql().contains("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"));
        db.apply(plan);
        assertEquals(500, db.length);
        assertEquals(1, db.executed.size());
        assertTrue(db.logs.stream().anyMatch(log -> log.startsWith("[已扩长]")));
        db.apply(plan);
        assertEquals(1, db.executed.size());
    }

    /** 执行后真实排序规则变化仍应停止，并给出阶段和前后完整属性。 */
    @Test public void reportsPostAlterAttributeMismatch() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan.Table plan = db.plan();
        db.changeCollation = true;
        SQLException error = assertThrows(SQLException.class, () -> db.apply(plan));
        assertTrue(error.getMessage().contains("失败阶段=扩长后验证"));
        assertTrue(error.getMessage().contains("utf8mb4_bin"));
        assertTrue(error.getMessage().contains("utf8mb4_general_ci"));
        assertTrue(error.getMessage().contains("已确认完成 0 项"));
        assertTrue(db.logs.stream().anyMatch(log -> log.startsWith("[执行扩长]")));
        assertEquals(1, db.executed.size());
    }

    /** 预览后的目录属性变化在执行任何 DDL 前拒绝。 */
    @Test public void rejectsPreAlterAttributeMismatch() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan.Table plan = db.plan();
        db.collation = "utf8mb4_general_ci";
        SQLException error = assertThrows(SQLException.class, () -> db.apply(plan));
        assertTrue(error.getMessage().contains("预览="));
        assertTrue(error.getMessage().contains("当前="));
        assertTrue(db.executed.isEmpty());
    }

    /** ALTER 执行或提交失败与执行后定义验证失败明确区分。 */
    @Test public void reportsCommitFailureStage() throws Exception {
        Fixture db = new Fixture();
        SchemaPlan.Table plan = db.plan();
        db.failCommit = true;
        SQLException error = assertThrows(SQLException.class, () -> db.apply(plan));
        assertTrue(error.getMessage().contains("失败阶段=扩长执行或提交"));
        assertTrue(error.getMessage().contains("commit failed"));
    }

    /** 模拟 MySQL 省略字符集、ALTER 后补出字符集的目录行为。 */
    private static final class Fixture {
        final MySQLDialect dialect = new MySQLDialect();
        final List<String> executed = new ArrayList<>(), logs = new ArrayList<>();
        int length = 100;
        String collation = "utf8mb4_bin";
        boolean changeCollation, failCommit;
        final Connection connection = proxy(Connection.class, (method, args) -> {
            if (method.equals("getCatalog")) return "db";
            if (method.equals("commit") && failCommit) throw new SQLException("commit failed");
            if (method.equals("prepareStatement")) return proxy(PreparedStatement.class, (op, params) -> {
                if (!op.equals("executeQuery")) return null;
                int[] index = {0};
                return proxy(ResultSet.class, (read, values) -> switch (read) {
                    case "next" -> index[0]++ == 0;
                    case "wasNull" -> false;
                    case "getLong" -> (long) length;
                    case "getString" -> switch ((int) values[0]) {
                        case 1 -> "first_pinyin";
                        case 2 -> "varchar";
                        case 4 -> "utf8mb4";
                        case 5 -> collation;
                        default -> null;
                    };
                    default -> null;
                });
            });
            if (method.equals("createStatement")) return proxy(Statement.class, (op, params) -> {
                if (op.equals("execute")) {
                    executed.add((String) params[0]);
                    length = 500;
                    if (changeCollation) collation = "utf8mb4_general_ci";
                    return false;
                }
                if (op.equals("executeQuery")) {
                    int[] index = {0};
                    return proxy(ResultSet.class, (read, values) -> switch (read) {
                        case "next" -> index[0]++ == 0;
                        case "getString" -> "CREATE TABLE `kb_category` (`first_pinyin` varchar(" + length + ")"
                                + (executed.isEmpty() ? "" : " CHARACTER SET utf8mb4")
                                + " COLLATE " + collation + " DEFAULT NULL)";
                        default -> null;
                    });
                }
                return null;
            });
            return null;
        });

        /** 通过实际目录读取与比较生成已预览计划。 */
        SchemaPlan.Table plan() throws SQLException {
            SchemaPlan.Table result = new SchemaPlan.Table("kb_category");
            SchemaPreparation.compare(dialect, CharacterColumns.read(connection, dialect, "", "kb_category"),
                    List.of("first_pinyin"), Map.of("FIRST_PINYIN", "VARCHAR2(500)"), result, logs::add);
            return result;
        }

        /** 运行真实结构变更流程。 */
        void apply(SchemaPlan.Table plan) throws SQLException {
            SchemaPreparation.applyChanges(connection, dialect, "", "kb_category", plan, logs::add, () -> false);
        }
    }
}
