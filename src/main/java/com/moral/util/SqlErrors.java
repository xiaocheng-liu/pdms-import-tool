package com.moral.util;

import java.sql.SQLException;

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
        if (error.getErrorCode() == ORACLE_CANCEL_CODE) {
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
                || message.contains("取消")
                || message.contains("超时");
    }
}
