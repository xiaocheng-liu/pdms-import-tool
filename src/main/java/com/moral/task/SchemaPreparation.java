package com.moral.task;

import com.moral.csv.CsvMeta;
import com.moral.csv.CsvMetaReader;
import com.moral.csv.DdlBuilder;
import com.moral.csv.OracleColumnType;
import com.moral.csv.OracleTableDefinitions;
import com.moral.db.CharacterColumns;
import com.moral.db.AddedColumnVerifier;
import com.moral.db.Dialect;
import com.moral.model.ConnectionConfig;
import com.moral.model.CsvTable;
import com.moral.model.ImportOptions;
import com.moral.model.SchemaPlan;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 导入前结构预检与已确认字段变更计划的执行，不负责清表和数据写入。 */
public final class SchemaPreparation {
    private SchemaPreparation() { }

    /** 每张表独立保存检查结果，失败表不能通过空计划混入导入。 */
    public static SchemaPlan prepare(Connection connection, Dialect dialect, ConnectionConfig config,
                                     ImportOptions options, List<CsvTable> tables, Consumer<String> logger) {
        SchemaPlan plan = new SchemaPlan();
        OracleTableDefinitions definitions = new OracleTableDefinitions();
        for (CsvTable table : tables) {
            SchemaPlan.Table result = new SchemaPlan.Table(table.getTableName());
            plan.tables().put(table.getTableName(), result);
            Consumer<String> note = message -> { result.notes().add(message); logger.accept(message); };
            try {
                boolean exists = dialect.tableExists(connection, config.getSchema(), table.getTableName());
                if (!exists && !options.isAutoCreateTable()) {
                    throw new SQLException("目标表不存在，未开启自动建表");
                }
                if (exists && !options.isSyncColumnLengths() && !options.isAddMissingColumns() && !options.isSyncComments()) continue;
                CsvMeta meta = CsvMetaReader.read(table.getFile(), options.getEncoding());
                if (!meta.isValid()) throw new SQLException("CSV 表头为空或解析失败");
                if (!exists) {
                    definitions.apply(table, meta, options.getEncoding(), dialect, config.getSchema(), note);
                    result.setCreateSql(DdlBuilder.build(dialect, config.getSchema(), table.getTableName(), meta));
                } else {
                    Map<String, String> source = definitions.columns(table, options.getEncoding(), config.getSchema(), note);
                    if (source.isEmpty()) {
                        note.accept("[提示] " + table.getTableName() + "：无可匹配的源字段定义，不执行字段变更");
                    } else if (options.isSyncColumnLengths() || options.isAddMissingColumns()) {
                        CharacterColumns.Table target = CharacterColumns.read(connection, dialect, config.getSchema(), table.getTableName());
                        compare(dialect, target, meta.getColumns(), source, result, note,
                                options.isSyncColumnLengths(), options.isAddMissingColumns());
                    }
                }
                if (options.isSyncComments()) {
                    SchemaComments.prepare(connection, dialect, config.getSchema(), table.getTableName(), meta, exists,
                            definitions.comments(table, options.getEncoding(), config.getSchema(), note), result);
                }
            } catch (Exception error) {
                result.setError(error.getMessage() == null ? error.toString() : error.getMessage());
                logger.accept("[检查失败] " + table.getTableName() + "：" + result.error());
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            }
        }
        return plan;
    }

    /** 只比较 CSV 使用的列，转换后的字符类型和单位一致时才允许增大长度。 */
    static void compare(Dialect dialect, CharacterColumns.Table target, List<String> csvColumns,
                        Map<String, String> source, SchemaPlan.Table result, Consumer<String> note) throws SQLException {
        compare(dialect, target, csvColumns, source, result, note, true, false);
    }

    /** 新增与扩长独立开关；缺失列必须有可靠的脚本类型。 */
    static void compare(Dialect dialect, CharacterColumns.Table target, List<String> csvColumns,
                        Map<String, String> source, SchemaPlan.Table result, Consumer<String> note,
                        boolean widen, boolean add) throws SQLException {
        for (String csvColumn : csvColumns) {
            String name = dialect.normalizeTableName(csvColumn);
            CharacterColumns.Column current = target.columns().get(name);
            String declaration = source.get(csvColumn.toUpperCase(Locale.ROOT));
            if (current == null) {
                if (!add) throw new SQLException("目标表缺少列 " + target.name() + "." + name + "，未开启补充缺失字段");
                OracleColumnType mapped = declaration == null ? null : OracleColumnType.map(declaration, dialect.type());
                if (mapped == null) throw new SQLException("缺失列 " + name + " 无源定义或类型无法可靠转换，请人工处理");
                String columnSql = CharacterColumns.identifier(dialect, name) + " " + mapped.sql() + " NULL";
                String sql = "ALTER TABLE " + CharacterColumns.qualified(dialect, target.schema(), target.name())
                        + switch (dialect.type()) {
                            case ORACLE, DAMENG -> " ADD (" + columnSql + ")";
                            default -> " ADD COLUMN " + columnSql;
                        };
                result.additions().add(new SchemaPlan.Addition(target.schema(), target.name(), name, mapped.sql(), sql));
                note.accept("[新增字段] " + result.name() + "." + name + "：" + declaration + " → " + mapped.sql()
                        + "，允许 NULL，不复制源默认值、非空及其他约束");
                continue;
            }
            if (!widen) continue;
            if (declaration == null) {
                note.accept("[提示] " + result.name() + "." + name + "：源脚本未定义该列，不扩长");
                continue;
            }
            OracleColumnType mapped = OracleColumnType.map(declaration, dialect.type());
            String desired = mapped == null ? declaration : mapped.sql();
            if (mapped != null && sameTypeFamily(desired, current.type())) continue;
            Matcher chars = Pattern.compile("(?i)^(VARCHAR2|VARCHAR|NVARCHAR2|NVARCHAR|CHAR|NCHAR)\\((\\d+)(?: (BYTE|CHAR))?\\)$")
                    .matcher(desired);
            if (!chars.matches() || !current.isCharacter()
                    || !current.type().equals(CharacterColumns.normalizeType(chars.group(1)))) {
                note.accept("[提示] " + result.name() + "." + name + "：源定义 " + declaration
                        + "，目标 " + current.display() + "，非同类型字符扩长，保持目标定义");
                continue;
            }
            Matcher explicitUnit = Pattern.compile("(?i)\\b(BYTE|CHAR)\\s*\\)\\s*$").matcher(declaration);
            if (explicitUnit.find() && !current.unit().equals(explicitUnit.group(1).toUpperCase(Locale.ROOT))) {
                note.accept("[提示] " + result.name() + "." + name + "：源与目标长度单位不同，不自动修改");
                continue;
            }
            if (dialect.type() == com.moral.model.DbType.KINGBASE && chars.group(3) != null
                    && !current.unit().equalsIgnoreCase(chars.group(3))) {
                note.accept("[提示] " + result.name() + "." + name + "：目标 " + current.display()
                        + " 与源转换类型 " + desired + " 长度单位不同，不自动转换；请确认后调整目标列单位");
                continue;
            }
            int length = Integer.parseInt(chars.group(2));
            if (length <= current.length()) {
                if (length < current.length()) note.accept("[提示] " + result.name() + "." + name
                        + "：源长度 " + length + " 小于目标 " + current.length() + "，不缩短");
                continue;
            }
            String sql = CharacterColumns.widenSql(dialect, target.schema(), target.name(), current, length);
            if (sql == null) {
                note.accept("[提示] " + result.name() + "." + name + "：无法可靠保留完整列属性，请人工扩长");
                continue;
            }
            result.widenings().add(new SchemaPlan.Widening(target.schema(), target.name(), current, length, sql));
        }
    }

    /** 目录通常只返回基础类型，带精度的数值声明仍属于同一类型族。 */
    private static boolean sameTypeFamily(String desired, String actual) {
        String source = CharacterColumns.normalizeType(desired);
        String target = CharacterColumns.normalizeType(actual);
        if (source.equals(target)) return true;
        if (source.matches("^(NUMBER|NUMERIC|DECIMAL)(?:\\(.*\\))?$")
                && target.matches("^(NUMBER|NUMERIC|DECIMAL)$")) return true;
        return source.matches("^(DATE|DATETIME|TIMESTAMP)(?:\\(.*\\))?$")
                && target.matches("^(DATE|DATETIME|TIMESTAMP)$");
    }

    /** 严格执行单条语句，提交异常不得当成隐式提交忽略。 */
    public static void executeDdl(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
        connection.commit();
    }

    /** 执行用户确认的新增与扩长计划；全部操作先复核，逐条执行后再读取实际定义验证。 */
    public static void applyChanges(Connection connection, Dialect dialect, String schema, String table,
                                      SchemaPlan.Table plan, Consumer<String> logger,
                                      BooleanSupplier cancelled) throws SQLException {
        if (plan == null || (plan.widenings().isEmpty() && plan.additions().isEmpty() && plan.comments().isEmpty())) return;
        checkCancelled(cancelled);
        SchemaComments.precheck(connection, dialect, plan);
        CharacterColumns.Table before = CharacterColumns.read(connection, dialect, schema, table);
        for (SchemaPlan.Widening change : plan.widenings()) validate(before, change);
        for (SchemaPlan.Addition change : plan.additions()) validateMissing(before, change);
        int completed = 0;
        String phase = "执行前复核";
        String activeColumn = "";
        try {
            for (SchemaPlan.Addition change : plan.additions()) {
                activeColumn = change.column();
                phase = "新增前复核";
                checkCancelled(cancelled);
                validateMissing(CharacterColumns.read(connection, dialect, schema, table), change);
                phase = "新增执行或提交";
                logger.accept("[执行新增] " + change.sql());
                executeDdl(connection, change.sql());
                phase = "新增后验证";
                AddedColumnVerifier.verify(connection, dialect, change);
                completed++;
                logger.accept("[已新增] " + table + "." + change.column() + "：" + change.declaration());
            }
            for (SchemaPlan.Widening change : plan.widenings()) {
                activeColumn = change.before().name();
                phase = "扩长前复核";
                checkCancelled(cancelled);
                CharacterColumns.Table fresh = CharacterColumns.read(connection, dialect, schema, table);
                CharacterColumns.Column current = validate(fresh, change);
                if (current.length() >= change.length()) {
                    logger.accept("[扩长] " + table + "." + current.name() + " 已达到目标长度，跳过");
                    continue;
                }
                phase = "扩长执行或提交";
                logger.accept("[执行扩长] " + change.sql());
                executeDdl(connection, change.sql());
                phase = "扩长后验证";
                CharacterColumns.Column after = validate(CharacterColumns.read(connection, dialect, schema, table), change);
                if (after.length() < change.length()) throw new SQLException("执行后长度未达到 " + change.length());
                completed++;
                logger.accept("[已扩长] " + table + "." + current.name() + "：" + current.display() + " → " + after.display());
            }
            phase = "注释同步或验证";
            SchemaComments.apply(connection, dialect, plan, logger, cancelled);
        } catch (SQLException error) {
            throw new SQLException("字段结构变更失败，已确认完成 " + completed + " 项；停止该表导入。"
                    + "已执行 DDL 或提交结果不明确的操作需核对，重试会重新比较。"
                    + "失败阶段=" + phase + "，字段=" + table + "." + activeColumn + "：" + error.getMessage(), error);
        }
    }

    /** 新增列在执行前必须仍然缺失；并发新增需重新预览。 */
    private static void validateMissing(CharacterColumns.Table actual, SchemaPlan.Addition change) throws SQLException {
        if (!actual.schema().equals(change.schema()) || !actual.name().equals(change.table())
                || actual.columns().containsKey(change.column())) {
            throw new SQLException("目标定义已变化，请重新预览：" + change.table() + "." + change.column());
        }
    }

    /** 防止预览后对象、类型、单位或 MySQL 其他属性变化导致执行旧方案。 */
    private static CharacterColumns.Column validate(CharacterColumns.Table actual, SchemaPlan.Widening change) throws SQLException {
        CharacterColumns.Column current = actual.columns().get(change.before().name());
        if (!actual.schema().equals(change.schema()) || !actual.name().equals(change.table()) || current == null
                || !CharacterColumns.sameDefinition(change.before(), current) || current.length() < change.before().length()) {
            throw new SQLException("目标定义已变化，请重新预览：" + change.table() + "." + change.before().name()
                    + "；预览目标=" + change.schema() + "." + change.table()
                    + "，当前目标=" + actual.schema() + "." + actual.name()
                    + "；预览=" + change.before().diagnostic()
                    + "；当前=" + (current == null ? "列不存在" : current.diagnostic()));
        }
        return current;
    }

    /** 停止后不开始下一条结构变更。 */
    private static void checkCancelled(BooleanSupplier cancelled) throws SQLException {
        if (cancelled.getAsBoolean()) throw new SQLException("导入已取消", "57014");
    }
}
