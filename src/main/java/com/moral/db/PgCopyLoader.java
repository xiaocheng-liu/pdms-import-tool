package com.moral.db;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * PostgreSQL COPY 批量加载：直接把 CSV 字节流交给服务端解析，比逐行 INSERT 快数倍。
 * 仅在极速模式下使用，且类被单独隔离，未使用 PG 驱动时不会影响其它数据库。
 */
public final class PgCopyLoader {

    private static final int BUFFER_SIZE = 64 * 1024;

    private PgCopyLoader() {
    }

    /**
     * 执行 COPY FROM STDIN。
     *
     * @param conn        PG 连接（由调用方负责提交/回滚）
     * @param copySql      COPY 语句
     * @param data         CSV 字节流（UTF-8）
     * @param bytesConsumer 已写入字节数回调，用于进度展示
     * @param cancelled    取消判断
     * @return 成功加载的行数（被取消时返回 0，且数据不提交）
     */
    public static long copyIn(Connection conn, String copySql, InputStream data,
                              LongConsumer bytesConsumer, BooleanSupplier cancelled)
            throws SQLException, IOException {
        CopyManager manager = conn.unwrap(PGConnection.class).getCopyAPI();
        CopyIn copyIn = manager.copyIn(copySql);
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int read;
            while ((read = data.read(buffer)) > 0) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    copyIn.cancelCopy();
                    return 0;
                }
                copyIn.writeToCopy(buffer, 0, read);
                if (bytesConsumer != null) {
                    bytesConsumer.accept(read);
                }
            }
            return copyIn.endCopy();
        } catch (SQLException | IOException | RuntimeException error) {
            try {
                copyIn.cancelCopy();
            } catch (SQLException ignore) {
                // 取消失败由调用方回滚处理
            }
            throw error;
        }
    }
}
