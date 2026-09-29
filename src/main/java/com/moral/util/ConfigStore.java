package com.moral.util;

import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.model.ImportOptions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量配置持久化（UTF-8 的 key=value 文件，避免引入 JSON 依赖）。
 * 存放位置：用户主目录下的 .pdms-import-tool/config.properties
 */
public final class ConfigStore {

    private static final String DIR_NAME = ".pdms-import-tool";
    private static final String FILE_NAME = "config.properties";
    private static final Charset CHARSET = StandardCharsets.UTF_8;

    private ConfigStore() {
    }

    public static File configFile() {
        Path dir = Paths.get(System.getProperty("user.home"), DIR_NAME);
        return dir.resolve(FILE_NAME).toFile();
    }

    /** 保存连接、选项、最近目录 */
    public static synchronized void save(ConnectionConfig conn, ImportOptions options, String lastDir, boolean savePassword) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("db.type", conn.getDbType().name());
        data.put("db.host", conn.getHost());
        data.put("db.port", String.valueOf(conn.getPort()));
        data.put("db.database", conn.getDatabase());
        data.put("db.user", conn.getUser());
        data.put("db.schema", conn.getSchema());
        data.put("db.savePassword", String.valueOf(savePassword));
        data.put("db.password", savePassword ? conn.getPassword() : "");

        data.put("opt.threads", String.valueOf(options.getThreads()));
        data.put("opt.batchSize", String.valueOf(options.getBatchSize()));
        data.put("opt.shardThresholdMb", String.valueOf(options.getShardThresholdMb()));
        data.put("opt.maxShardsPerTable", String.valueOf(options.getMaxShardsPerTable()));
        data.put("opt.fastMode", String.valueOf(options.isFastMode()));
        data.put("opt.encoding", options.getEncoding().name());
        data.put("opt.emptyAsNull", String.valueOf(options.isEmptyAsNull()));
        data.put("opt.clearMode", options.getClearMode().name());
        data.put("opt.autoCreateTable", String.valueOf(options.isAutoCreateTable()));
        data.put("opt.continueOnError", String.valueOf(options.isContinueOnError()));
        data.put("opt.writeTimeoutSeconds", String.valueOf(options.getWriteTimeoutSeconds()));
        data.put("last.dir", lastDir == null ? "" : lastDir);

        try {
            File file = configFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), CHARSET)) {
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    writer.write(entry.getKey() + "=" + escape(entry.getValue()));
                    writer.newLine();
                }
            }
        } catch (IOException ignore) {
            // 配置保存失败不影响主流程
        }
    }

    /** 读取配置，返回 key/value；使用默认值填充缺失项 */
    public static synchronized Map<String, String> load() {
        Map<String, String> data = new LinkedHashMap<>();
        File file = configFile();
        if (!file.exists()) {
            return data;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), CHARSET)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int index = line.indexOf('=');
                if (index <= 0) {
                    continue;
                }
                data.put(line.substring(0, index).trim(), unescape(line.substring(index + 1)));
            }
        } catch (IOException ignore) {
            // 读取失败时返回已解析部分
        }
        return data;
    }

    public static void applyTo(ConnectionConfig conn, Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        conn.setDbType(DbType.of(data.get("db.type")));
        conn.setHost(orDefault(data.get("db.host"), conn.getHost()));
        conn.setPort(parseInt(data.get("db.port"), conn.getPort()));
        conn.setDatabase(orDefault(data.get("db.database"), conn.getDatabase()));
        conn.setUser(orDefault(data.get("db.user"), conn.getUser()));
        conn.setSchema(orDefault(data.get("db.schema"), conn.getSchema()));
        conn.setPassword(orDefault(data.get("db.password"), ""));
    }

    public static void applyTo(ImportOptions options, Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        options.setThreads(parseInt(data.get("opt.threads"), options.getThreads()));
        options.setBatchSize(parseInt(data.get("opt.batchSize"), options.getBatchSize()));
        options.setShardThresholdMb(parseInt(data.get("opt.shardThresholdMb"), options.getShardThresholdMb()));
        options.setMaxShardsPerTable(parseInt(data.get("opt.maxShardsPerTable"), options.getMaxShardsPerTable()));
        options.setFastMode(parseBoolean(data.get("opt.fastMode"), options.isFastMode()));
        options.setEncoding(parseCharset(data.get("opt.encoding")));
        options.setEmptyAsNull(parseBoolean(data.get("opt.emptyAsNull"), options.isEmptyAsNull()));
        options.setClearMode(parseClearMode(data.get("opt.clearMode")));
        options.setAutoCreateTable(parseBoolean(data.get("opt.autoCreateTable"), options.isAutoCreateTable()));
        options.setContinueOnError(parseBoolean(data.get("opt.continueOnError"), options.isContinueOnError()));
        options.setWriteTimeoutSeconds(parseInt(data.get("opt.writeTimeoutSeconds"), options.getWriteTimeoutSeconds()));
    }

    public static String lastDir(Map<String, String> data) {
        return data == null ? "" : orDefault(data.get("last.dir"), "");
    }

    public static boolean savePassword(Map<String, String> data) {
        return parseBoolean(data == null ? null : data.get("db.savePassword"), false);
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static Charset parseCharset(String name) {
        if (name == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (Exception ignore) {
            return StandardCharsets.UTF_8;
        }
    }

    private static ImportOptions.ClearMode parseClearMode(String name) {
        if (name == null) {
            return ImportOptions.ClearMode.NONE;
        }
        try {
            return ImportOptions.ClearMode.valueOf(name);
        } catch (Exception ignore) {
            return ImportOptions.ClearMode.NONE;
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("=", "\\=");
    }

    private static String unescape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case 'r':
                        builder.append('\r');
                        i++;
                        break;
                    case 'n':
                        builder.append('\n');
                        i++;
                        break;
                    case '\\':
                        builder.append('\\');
                        i++;
                        break;
                    case '=':
                        builder.append('=');
                        i++;
                        break;
                    default:
                        builder.append(c);
                        break;
                }
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
