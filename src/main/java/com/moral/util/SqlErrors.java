package com.moral.util;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 错误归类：识别"取消 / 超时"类错误。
 *
 * <p>看门狗自动取消当前批次（{@code Statement.cancel()}）或驱动查询超时触发时，
 * JDBC 会抛出这类错误。它们的 SQLState 通常不属于 22/23/21 等"数据行错误"，
 * 若被当作普通数据行错误处理，会被"失败跳过继续"逻辑静默吞掉，导致整批数据丢失
 * 但表仍被标记为成功，因此必须单独识别并强制上抛。
 */
public final class SqlErrors {

    /** PostgreSQL 取消查询 */
    private static final String PG_CANCEL_STATE = "57014";
    /** JDBC 标准取消/超时状态码（部分驱动） */
    private static final String JDBC_CANCEL_STATE = "HY008";
    /** Oracle ORA-01013：user requested cancel of current operation（cancel 与 query timeout 均抛此码） */
    private static final int ORACLE_CANCEL_CODE = 1013;
    /** MySQL ER_QUERY_INTERRUPTED：Query execution was interrupted（cancel / KILL QUERY） */
    private static final int MYSQL_CANCEL_CODE = 3024;

    /**
     * pgjdbc / 金仓驱动的批量异常会回显整条 SQL 与全部参数值，形如：
     * {@code Batch entry 44 INSERT INTO "t" (...) VALUES (('<整篇 HTML>'), ...) was aborted: ERROR: xxx}。
     * 长文本字段（如 content）会把真正的数据库原因挤到消息末尾，日志/明细按长度截断后只剩 SQL 回显，
     * 看不到任何可用信息，因此必须先剥离这段回显。
     */
    private static final Pattern BATCH_SQL_ECHO = Pattern.compile(
            "Batch entry \\d+\\s+(?:INSERT|UPDATE|DELETE|MERGE|SELECT|COPY)[\\s\\S]*?was aborted:\\s*",
            Pattern.CASE_INSENSITIVE);
    /** 批量异常尾部提示语：Call getNextException to see other errors in the batch. */
    private static final Pattern NEXT_EXCEPTION_HINT = Pattern.compile(
            "\\s*Call getNextException[^。.]*[。.]?\\s*$", Pattern.CASE_INSENSITIVE);
    /** 兜底：整条消息以 SQL 回显开头且没有 was aborted 时，直接丢掉 SQL 主体 */
    private static final Pattern LEADING_SQL = Pattern.compile(
            "^(?:Batch entry \\d+\\s+)?(?:INSERT|UPDATE|DELETE|MERGE|COPY)\\s+INTO\\s+.*?VALUES\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private SqlErrors() {
    }

    /** 是否为"取消 / 超时"类错误（不含数据行本身的问题） */
    public static boolean isCancelOrTimeout(SQLException error) {
        if (error == null) {
            return false;
        }
        String state = error.getSQLState();
        if (PG_CANCEL_STATE.equals(state) || JDBC_CANCEL_STATE.equals(state)) {
            return true;
        }
        if (error.getErrorCode() == ORACLE_CANCEL_CODE || error.getErrorCode() == MYSQL_CANCEL_CODE) {
            return true;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("cancel")
                || lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("interrupted")
                || message.contains("取消")
                || message.contains("超时");
    }

    /**
     * 取出真正有用的数据库原因：剥掉驱动回显的 SQL 与参数值、去掉尾部提示语、压成单行并截断。
     *
     * @param error     原始异常
     * @param maxLength 截断长度
     */
    public static String readableMessage(SQLException error, int maxLength) {
        if (error == null) {
            return "";
        }
        String text = error.getMessage();
        if (text == null) {
            return "";
        }
        text = text.replace('\r', ' ').replace('\n', ' ');
        text = BATCH_SQL_ECHO.matcher(text).replaceFirst("");
        text = NEXT_EXCEPTION_HINT.matcher(text).replaceFirst("");
        if (LEADING_SQL.matcher(text).find()) {
            // 未能匹配 was aborted 的回显：保留消息末尾 300 字符（数据库原因通常在末尾）
            text = text.length() > 300 ? "..." + text.substring(text.length() - 300) : text;
        }
        text = text.trim();
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength) + "...";
        }
        return text;
    }

    /** {@link #readableMessage(SQLException, int)} 的默认长度（500 字符） */
    public static String readableMessage(SQLException error) {
        return readableMessage(error, 500);
    }

    /**
     * {@code XX000 cache lookup failed for type xxx}：服务端查不到某个类型 OID。
     *
     * <p>两种成因，都不是"数据行"的问题，且会让<b>整库所有行</b>报同一句话：
     * <ol>
     *   <li>驱动与服务端对参数类型 OID 的约定不一致（如 Kingbase 旧版本 + 新版 kingbase8 驱动，
     *       服务端收到的 OID 比实际值多了 2^29 偏移），此时任何带参数的 PreparedStatement 都执行不了；</li>
     *   <li>目标表被删除重建，会话/计划缓存里残留旧的行类型 OID。</li>
     * </ol>
     * 因此它必须被识别为环境性错误并按"整批/整表"处理，不能当成个别坏行去逐行重放。
     */
    public static boolean isUnknownTypeLookup(SQLException error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        return "XX000".equals(error.getSQLState())
                && message.toLowerCase(Locale.ROOT).contains("cache lookup failed for type");
    }

    /** 54 = program_limit_exceeded：服务端资源/结构限制（索引行大小、行大小、内存等） */
    public static boolean isProgramLimit(SQLException error) {
        if (error == null) {
            return false;
        }
        String state = error.getSQLState();
        return state != null && state.startsWith("54");
    }

    /**
     * 54 类中由"某行数据过大"触发的限制：PG / 金仓的 btree 索引单条目超限（约 2704 字节）、
     * 行大小超过页上限等。这类限制因具体行的字段长度而异，逐行重放可让好行入库、坏行精确定位，
     * 因此归入"数据行错误"而不是整批丢弃。
     */
    public static boolean isRowSizeLimit(SQLException error) {
        if (!isProgramLimit(error)) {
            return false;
        }
        String text = readableMessage(error, 2000).toLowerCase(Locale.ROOT);
        return text.contains("index row size")
                || text.contains("index row requires")
                || text.contains("row is too big")
                || text.contains("maximum size")
                || text.contains("exceeds maximum");
    }
}
