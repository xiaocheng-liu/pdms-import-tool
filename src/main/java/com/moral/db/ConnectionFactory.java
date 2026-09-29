package com.moral.db;

import com.moral.model.ConnectionConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 连接创建与测试：显式加载驱动，统一出口。
 *
 * <p>除登录超时外，额外设置"网络读取超时"，避免数据库端无响应（锁等待、服务端假死等）
 * 时 JDBC 调用无限期挂起，让导入一直停在"提交中"。
 */
public final class ConnectionFactory {

    /** 连接超时（秒），避免界面长时间卡住 */
    private static final int LOGIN_TIMEOUT = 15;
    /** 建连阶段的网络超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 15 * 1000;
    /** 未配置写入超时时的默认网络读取超时（毫秒）：10 分钟 */
    private static final int DEFAULT_NETWORK_TIMEOUT_MS = 10 * 60 * 1000;

    /** 驱动执行超时强制任务所需的线程池（守护线程，不影响退出） */
    private static final ExecutorService TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jdbc-timeout-enforcer");
        thread.setDaemon(true);
        return thread;
    });

    private ConnectionFactory() {
    }

    /** 不指定写入超时时使用默认网络超时的建连入口（测试连接等场景） */
    public static Connection create(ConnectionConfig cfg) throws SQLException {
        return create(cfg, 0);
    }

    /**
     * @param writeTimeoutSeconds 写入超时（秒）；仅在网络超时基础上取更大值，
     *                            网络超时永远不小于 {@link #DEFAULT_NETWORK_TIMEOUT_MS}，
     *                            避免把"写得慢但正常"的批次误杀
     */
    public static Connection create(ConnectionConfig cfg, int writeTimeoutSeconds) throws SQLException {
        Dialect dialect = DialectFactory.get(cfg.getDbType());
        loadDriver(dialect.driverClass());
        int networkMillis = writeTimeoutSeconds > 0
                ? Math.max(writeTimeoutSeconds * 1000, DEFAULT_NETWORK_TIMEOUT_MS)
                : DEFAULT_NETWORK_TIMEOUT_MS;
        Properties props = new Properties();
        props.setProperty("user", cfg.getUser());
        props.setProperty("password", cfg.getPassword() == null ? "" : cfg.getPassword());
        // Oracle 中文环境：确保驱动按 UTF-8 处理字符串
        if (cfg.getDbType() == com.moral.model.DbType.ORACLE) {
            props.setProperty("oracle.jdbc.defaultNChar", "false");
            props.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(CONNECT_TIMEOUT_MS));
            props.setProperty("oracle.net.READ_TIMEOUT", String.valueOf(networkMillis));
        }
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT);
        Connection conn = DriverManager.getConnection(dialect.buildUrl(cfg), props);
        conn.setAutoCommit(false);
        applyNetworkTimeout(conn, networkMillis);
        return conn;
    }

    /**
     * 设置 JDBC 标准网络超时（对 executeBatch/executeUpdate/commit 均生效）。
     * 部分驱动（老版本达梦等）可能不支持，必须兼容降级，不能让建连失败。
     */
    private static void applyNetworkTimeout(Connection conn, int networkMillis) {
        try {
            conn.setNetworkTimeout(TIMEOUT_EXECUTOR, networkMillis);
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException ignore) {
            // 驱动不支持网络超时：忽略，由看门狗与 Statement.cancel() 兜底
        }
    }

    /** 测试连接，返回数据库产品名与版本描述；失败时抛出 SQLException */
    public static String test(ConnectionConfig cfg) throws SQLException {
        Connection conn = null;
        try {
            conn = create(cfg);
            String name = conn.getMetaData().getDatabaseProductName();
            String version = conn.getMetaData().getDatabaseProductVersion();
            String user = conn.getMetaData().getUserName();
            return "连接成功：" + name + " " + version + "（用户：" + user + "）";
        } finally {
            closeQuietly(conn);
        }
    }

    public static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignore) {
                // 关闭失败忽略
            }
        }
    }

    private static void loadDriver(String driverClass) throws SQLException {
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new SQLException("未找到数据库驱动：" + driverClass + "，请确认 jar 包完整", e);
        }
    }
}
