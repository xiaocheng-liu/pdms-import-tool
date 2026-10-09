package com.moral.db;

import com.moral.model.ConnectionConfig;
import com.moral.model.DbType;
import com.moral.util.SqlErrors;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 参数化协议兼容性探测与降级。
 *
 * <p>现场现象（本地金仓 V008R003 + 随包发布的 V9 驱动）：<b>任何带参数的 PreparedStatement
 * 都执行不了</b>，服务端统一返回
 * {@code XX000 cache lookup failed for type 536871955}，
 * 结果表现为"成功 0 行、失败 N 行"，且每张表每条记录的报错一模一样（不是数据问题）。
 *
 * <p>成因：驱动在 Parse/Bind 阶段直接带上参数类型 OID，而该库和它预期的不一致
 * （varchar 报 536871955 = 2^29 + 1043、bigint 报 536870932 = 2^29 + 20、timestamp 报 536870912），
 * 服务端按这些 OID 在系统表里查不到对应类型。
 * 因此同一种 INSERT，用 Statement 拼死 SQL 能写进去，一旦改用 PreparedStatement 就全军覆没。
 *
 * <p>处置：连接建立前先做一次低成本探测；一旦确认该目标库存在这个不匹配，
 * 就对 PostgreSQL / 金仓的 JDBC URL 追加 {@code preferQueryMode=simple}
 * ——走 simple 查询协议：参数由驱动在客户端拼装，不再随报文携带类型 OID。
 * 实测该模式下 5000 行批量写入与 28KB/行的大文本均正常，批量插入不受影响，
 * 也不影响极速模式的 COPY 通道。
 */
public final class ProtocolCompat {

    /** 走 simple 协议的参数 */
    private static final String SIMPLE_MODE = "preferQueryMode=simple";

    /** 目标库 → 已确认需要的连接参数（空 Map 表示正常，无需降级） */
    private static final Map<String, String> COMPAT_PARAMS = new ConcurrentHashMap<>();

    private ProtocolCompat() {
    }

    /**
     * 每个目标库只探测一次：用一个不带任何业务含义的参数化语句验证扩展协议是否可用。
     *
     * <p>探测连接用 {@link ConnectionFactory#create(ConnectionConfig)} 打开，此时本类的降级标记尚未设置，
     * 不会递归；探测结论立即生效，后续所有连接（含本次导入的并发连接）都按降级参数打开。
     *
     * @param logger 诊断日志出口（可能为 null）
     */
    public static void detectOnce(ConnectionConfig cfg, Consumer<String> logger) {
        if (cfg == null || !isPgFamily(cfg.getDbType())) {
            // simple 查询协议只对 pgjdbc 系驱动有效，其它库不做探测也不做降级
            return;
        }
        String key = keyOf(cfg);
        if (COMPAT_PARAMS.containsKey(key)) {
            return;
        }
        if (!isExtendedProtocolUsable(cfg)) {
            COMPAT_PARAMS.put(key, SIMPLE_MODE);
            log(logger, "[兼容] 检测到数据库无法识别 JDBC 驱动发送的参数类型 OID"
                    + "（cache lookup failed for type，常见于 Kingbase 旧版本 + 新版驱动）："
                    + "已自动切换到 simple 查询协议重试，数据导入不受影响");
        } else {
            COMPAT_PARAMS.put(key, "");
        }
    }

    /** 建连时需要追加的 URL 参数（无降级时返回空串） */
    public static String urlParams(ConnectionConfig cfg) {
        if (cfg == null) {
            return "";
        }
        String params = COMPAT_PARAMS.get(keyOf(cfg));
        return params == null ? "" : params;
    }

    /** 测试用途：清空已缓存的结论 */
    static void reset() {
        COMPAT_PARAMS.clear();
    }

    /**
     * 用一个纯内存的参数化语句探测扩展协议是否正常。
     * 只用 {@code setString}，命中的正是最容易不匹配的字符串参数 OID，且完全不触碰业务表。
     */
    private static boolean isExtendedProtocolUsable(ConnectionConfig cfg) {
        Connection conn = null;
        try {
            conn = ConnectionFactory.create(cfg);
            try (PreparedStatement statement = conn.prepareStatement("SELECT ?::text")) {
                statement.setString(1, "1");
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                }
            }
            return true;
        } catch (SQLException error) {
            // 只有"服务端认不出类型 OID"才降级；其它错误（权限、语法、网络）保持原协议，
            // 让后续真正的插入把明确原因报出来，避免掩盖真实问题
            return !SqlErrors.isUnknownTypeLookup(error);
        } catch (RuntimeException error) {
            // 驱动/环境异常不应当影响后续判断，这里按"可用"处理，让真正的插入去暴露问题
            return true;
        } finally {
            ConnectionFactory.closeQuietly(conn);
        }
    }

    private static boolean isPgFamily(DbType type) {
        return type == DbType.POSTGRESQL || type == DbType.KINGBASE;
    }

    private static String keyOf(ConnectionConfig cfg) {
        return cfg.getDbType() + "|" + cfg.getHost() + ":" + cfg.getPort() + "|" + cfg.getDatabase();
    }

    private static void log(Consumer<String> logger, String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }
}
