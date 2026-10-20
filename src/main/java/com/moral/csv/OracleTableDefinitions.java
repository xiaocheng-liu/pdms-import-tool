package com.moral.csv;

import com.moral.db.Dialect;
import com.moral.model.ColumnProfile;
import com.moral.model.CsvTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 读取同目录的 Oracle _tables.sql，提取列类型和数据库注释，不执行原始脚本。 */
public final class OracleTableDefinitions {
    private final Map<Path, Map<String, TableDefinition>> cache = new HashMap<>();

    /** 表定义与来源文件名一起缓存，多个脚本时仍能准确显示来源。 */
    private record TableDefinition(String fileName, Map<String, String> columns, Comments comments) { }

    /** null 表示脚本没有声明表注释，空字符串表示明确清空注释。 */
    public record Comments(String table, Map<String, String> columns) {
        /** 统一做不可变拷贝：调用方后续修改不会影响缓存中的定义；null 视作无列注释 */
        public Comments {
            columns = columns == null ? Map.of() : Map.copyOf(columns);
        }

        public boolean isEmpty() { return table == null && hasNoColumnComments(columns); }

        /** 是否没有任何列注释：抽成具名谓词，语义比裸 {@code isEmpty()} 更直白 */
        private static boolean hasNoColumnComments(Map<String, String> map) { return map.isEmpty(); }

        /** 脚本未声明任何注释时的空结果 */
        public static Comments empty() { return new Comments(null, Map.of()); }

        /** 表注释：text 为 null 表示未声明，空字符串表示明确清空 */
        public static Comments ofTable(String text) { return new Comments(text, Map.of()); }

        /** 单列注释，供 mergeComments 逐条并入 */
        public static Comments ofColumn(String column, String text) {
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put(column, text);
            return new Comments(null, columns);
        }
    }

    /** 按与字段类型相同的 Schema 规则取得注释。 */
    public Comments comments(CsvTable table, Charset charset, String schema, Consumer<String> logger) throws IOException {
        TableDefinition definition = find(table, charset, schema, logger);
        return definition == null ? Comments.empty() : definition.comments();
    }

    /** 按列名覆盖采样类型；每次检查或导入中，同一个目录只解析一次。 */
    public void apply(CsvTable table, CsvMeta meta, Charset charset, Dialect dialect,
                      String schema, Consumer<String> logger) throws IOException {
        TableDefinition definition = find(table, charset, schema, logger);
        if (definition == null) return;
        String name = normalize(table.getTableName());
        int matched = 0;
        for (ColumnProfile profile : meta.getProfiles()) {
            String declaration = definition.columns().get(normalize(profile.getName()));
            OracleColumnType mapped = declaration == null ? null : OracleColumnType.map(declaration, dialect.type());
            if (declaration == null) {
                logger.accept("[提示] " + name + "." + profile.getName()
                        + "：" + definition.fileName() + " 未定义此列，继续使用 CSV 采样类型");
                continue;
            }
            if (mapped == null) {
                throw new IOException(name + "." + profile.getName() + "：Oracle 类型 " + declaration
                        + " 暂无可靠的 " + dialect.type().getDisplayName() + " 等价映射，请手工确认建表类型");
            }
            if (dialect.type() == com.moral.model.DbType.ORACLE) {
                if (profile.getObservedMaxCharacters() > mapped.characterLimit()) {
                    throw new IOException(name + "." + profile.getName() + "：CSV 采样最大字符数 "
                            + profile.getObservedMaxCharacters() + " 超过脚本声明 " + mapped.sql()
                            + " 的长度，请调整列定义后重试");
                }
                if (mapped.sql().endsWith(" CHAR)")
                        && !declaration.toUpperCase(Locale.ROOT).matches(".*\\bCHAR\\s*\\)\\s*")) {
                    logger.accept("[建表] " + name + "." + profile.getName()
                            + "：脚本未指定长度单位，使用 " + mapped.sql());
                }
            }
            profile.setDeclaredSqlType(mapped.sql());
            profile.setType(mapped.binding());
            matched++;
            if (declaration.trim().equalsIgnoreCase("NUMBER") && dialect.type() == com.moral.model.DbType.MYSQL) {
                logger.accept("[提示] " + name + "." + profile.getName()
                        + "：无精度 NUMBER 转换为 DECIMAL(65,30)，整数最多 35 位、小数最多 30 位，请核对数据范围");
            }
        }
        logger.accept("[建表] " + name + "：已从 " + definition.fileName() + " 匹配 " + matched + "/"
                + meta.getProfiles().size() + " 列类型（" + dialect.type().getDisplayName() + "）");
    }

    /** 复用脚本缓存与 Schema 匹配，供已有字段扩长比较使用。 */
    public Map<String, String> columns(CsvTable table, Charset charset, String schema,
                                       Consumer<String> logger) throws IOException {
        TableDefinition definition = find(table, charset, schema, logger);
        return definition == null ? Map.of() : Map.copyOf(definition.columns());
    }

    /** 在同目录中寻找唯一匹配的源表定义。 */
    private TableDefinition find(CsvTable table, Charset charset, String schema,
                                 Consumer<String> logger) throws IOException {
        Path directory = table.getFile().toPath().toAbsolutePath().getParent();
        Map<String, TableDefinition> definitions = cache.get(directory);
        if (definitions == null) {
            definitions = new LinkedHashMap<>();
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith("_tables.sql")).sorted().toList()) {
                    try (InputStream input = CsvMetaReader.openWithoutBom(file.toFile())) {
                        String sql = new String(input.readAllBytes(), charset == null ? StandardCharsets.UTF_8 : charset);
                        Map<String, Map<String, String>> parsed = parse(sql);
                        Map<String, Comments> comments = parseComments(sql);
                        Set<String> names = new java.util.LinkedHashSet<>(parsed.keySet());
                        names.addAll(comments.keySet());
                        for (String name : names) {
                            TableDefinition previous = definitions.get(name);
                            Map<String, String> columns = parsed.getOrDefault(name, Map.of());
                            Comments notes = comments.getOrDefault(name, Comments.empty());
                            if (previous != null) {
                                if (!columns.isEmpty() && !previous.columns().isEmpty()) {
                                    throw new IOException("多个 _tables.sql 文件重复定义表 " + name);
                                }
                                if (columns.isEmpty()) columns = previous.columns();
                                notes = mergeComments(name, previous.comments(), notes);
                            }
                            definitions.put(name, new TableDefinition(file.getFileName().toString(), columns, notes));
                        }
                    }
                }
            }
            cache.put(directory, definitions);
        }
        if (definitions.isEmpty()) return null;
        String name = normalize(table.getTableName());
        TableDefinition definition = definitions.get(normalize(schema == null ? "" : schema) + "." + name);
        if (definition == null) {
            List<TableDefinition> candidates = definitions.entrySet().stream()
                    .filter(entry -> entry.getKey().equals(name) || entry.getKey().endsWith("." + name))
                    .map(Map.Entry::getValue).toList();
            if (candidates.size() == 1) definition = candidates.getFirst();
            else {
                logger.accept("[提示] " + name + "：_tables.sql " + (candidates.isEmpty()
                        ? "未找到表定义" : "存在多个 schema 的同名表，无法唯一匹配") + "，不使用该表脚本定义");
                return null;
            }
        }
        return definition;
    }

    /** 只解析 COMMENT ON 语句，不把普通 SQL 行注释当成数据库注释。 */
    static Map<String, Comments> parseComments(String sql) throws IOException {
        List<String> tokens = tokenize(sql);
        Map<String, Comments> result = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!is(tokens, i, "COMMENT") || !is(tokens, i + 1, "ON")) continue;
            boolean column = is(tokens, i + 2, "COLUMN");
            if (!column && !is(tokens, i + 2, "TABLE")) continue;
            int cursor = i + 3;
            List<String> names = new ArrayList<>();
            while (cursor < tokens.size()) {
                String identifier = tokens.get(cursor++);
                if (!(identifier.startsWith("\"") || identifier.matches("[\\p{L}_][\\p{L}\\p{N}_$#]*"))) {
                    throw new IOException("COMMENT ON 对象名无效");
                }
                names.add(normalize(identifier));
                if (!is(tokens, cursor, ".")) break;
                cursor++;
            }
            if (names.size() < (column ? 2 : 1) || names.size() > (column ? 3 : 2)
                    || !is(tokens, cursor++, "IS") || cursor >= tokens.size()) {
                throw new IOException("COMMENT ON 语句不完整");
            }
            String literal = tokens.get(cursor++);
            String text;
            if (literal.equalsIgnoreCase("NULL")) text = "";
            else if (literal.startsWith("'") && literal.endsWith("'")) {
                text = literal.substring(1, literal.length() - 1).replace("''", "'");
            } else if ((literal.startsWith("q'") || literal.startsWith("Q'")) && literal.length() >= 5) {
                text = literal.substring(3, literal.length() - 2);
            } else throw new IOException("COMMENT ON 仅支持字符串或 NULL");
            if (cursor < tokens.size() && !is(tokens, cursor, ";")) throw new IOException("COMMENT ON 语句结尾无效");
            String field = column ? names.removeLast() : null;
            String name = String.join(".", names);
            Comments comment = column ? Comments.ofColumn(field, text) : Comments.ofTable(text);
            result.put(name, mergeComments(name, result.getOrDefault(name, Comments.empty()), comment));
            i = cursor;
        }
        return result;
    }

    /** 拒绝重复注释声明，避免不同文件之间静默覆盖。 */
    private static Comments mergeComments(String name, Comments first, Comments second) throws IOException {
        if (first.table() != null && second.table() != null) throw new IOException("重复表注释：" + name);
        Map<String, String> columns = new LinkedHashMap<>(first.columns());
        for (var entry : second.columns().entrySet()) {
            if (columns.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IOException("重复字段注释：" + name + "." + entry.getKey());
            }
        }
        return new Comments(second.table() == null ? first.table() : second.table(), columns);
    }

    /** 解析 CREATE TABLE，括号层级保证 NUMBER(p,s)、默认值和表约束不会打乱列。 */
    static Map<String, Map<String, String>> parse(String sql) throws IOException {
        List<String> tokens = tokenize(sql);
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!is(tokens, i, "CREATE")) continue;
            int next = i + 1;
            if (is(tokens, next, "GLOBAL") && is(tokens, next + 1, "TEMPORARY")) next += 2;
            if (!is(tokens, next, "TABLE") || next + 2 >= tokens.size()) continue;
            String name = normalize(tokens.get(++next));
            next++;
            if (is(tokens, next, ".") && next + 1 < tokens.size()) {
                name += "." + normalize(tokens.get(next + 1));
                next += 2;
            }
            if (!is(tokens, next, "(")) continue;
            Map<String, String> columns = new LinkedHashMap<>();
            int depth = 1;
            int start = ++next;
            for (; next < tokens.size() && depth > 0; next++) {
                String token = tokens.get(next);
                if (token.equals("(")) depth++;
                if (token.equals(")")) depth--;
                if (depth == 0 || (depth == 1 && token.equals(","))) {
                    readColumn(tokens.subList(start, next), columns);
                    start = next + 1;
                }
            }
            if (depth != 0) throw new IOException("_tables.sql 的表 " + name + " 括号不完整");
            if (tables.putIfAbsent(name, columns) != null) throw new IOException("_tables.sql 重复定义表 " + name);
            i = next - 1;
        }
        return tables;
    }

    /** 忽略默认值和约束，仅保存列名与完整类型声明。 */
    private static void readColumn(List<String> tokens, Map<String, String> columns) throws IOException {
        if (tokens.size() < 2) return;
        Set<String> constraints = Set.of("CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "SUPPLEMENTAL");
        if (constraints.contains(tokens.getFirst().toUpperCase(Locale.ROOT))) return;
        Set<String> endings = Set.of("DEFAULT", "NOT", "NULL", "CONSTRAINT", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "ENABLE", "DISABLE", "COLLATE", "GENERATED");
        int end = 1;
        while (end < tokens.size() && !endings.contains(tokens.get(end).toUpperCase(Locale.ROOT))) end++;
        String name = normalize(tokens.getFirst());
        if (columns.putIfAbsent(name, String.join(" ", tokens.subList(1, end))) != null) {
            throw new IOException("_tables.sql 存在重复列 " + name);
        }
    }

    /** SQL 分词：注释丢弃，字符串与引用标识符保持整体，避免其中逗号和括号干扰。 */
    private static List<String> tokenize(String sql) throws IOException {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch) || ch == '\ufeff') { i++; continue; }
            if (sql.startsWith("--", i)) {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw new IOException("_tables.sql 注释未闭合");
                i = end + 2;
                continue;
            }
            int start = i;
            if ((ch == 'q' || ch == 'Q') && i + 2 < sql.length() && sql.charAt(i + 1) == '\'') {
                char open = sql.charAt(i + 2);
                char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
                int end = sql.indexOf("" + close + '\'', i + 3);
                if (end < 0) throw new IOException("_tables.sql 字符串未闭合");
                i = end + 2;
            } else if (ch == '\'' || ch == '"') {
                i++;
                boolean closed = false;
                while (i < sql.length()) {
                    if (sql.charAt(i++) == ch) {
                        if (i < sql.length() && sql.charAt(i) == ch) i++;
                        else { closed = true; break; }
                    }
                }
                if (!closed) throw new IOException("_tables.sql 引号未闭合");
            } else if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '#') {
                do { i++; } while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                        || "_$#".indexOf(sql.charAt(i)) >= 0));
            } else { i++; }
            tokens.add(sql.substring(start, i));
        }
        return tokens;
    }

    /** 与现有方言一致，表名和列名按不区分大小写匹配。 */
    private static String normalize(String name) {
        if (name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1).replace("\"\"", "\"");
        return name.toUpperCase(Locale.ROOT);
    }

    /** 安全比较当前位置的 SQL 关键字。 */
    private static boolean is(List<String> tokens, int index, String expected) {
        return index < tokens.size() && tokens.get(index).equalsIgnoreCase(expected);
    }
}
