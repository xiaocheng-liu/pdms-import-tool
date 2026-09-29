package com.moral.db;

import com.kingbase8.copy.CopyIn;
import com.kingbase8.copy.CopyManager;
import com.kingbase8.core.BaseConnection;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * 人大金仓 KingbaseES COPY 批量加载：与 PostgreSQL 同理，把 CSV 字节流交给服务端解析。
 *
 * <p>金仓驱动的 COPY API 与 PG 形似：{@code KBConnection#getCopyAPI()} 取到 {@link CopyManager}，
 * 再走 copyIn / writeToCopy / endCopy。本类单独隔离，仅 import com.kingbase8.*，
 * 不使用金仓时不会触发这些类的加载。
 */
public final class KingbaseCopyLoader {

    private static final int BUFFER_SIZE = 64 * 1024;

    private KingbaseCopyLoader() {
    }

    /**
     * 执行 COPY FROM STDIN。
     *
     * @param conn          金仓连接（由调用方负责提交/回滚）
     * @param copySql       COPY 语句
     * @param data          CSV 字节流（UTF-8）
     * @param bytesConsumer 已写入字节数回调，用于进度展示
     * @param cancelled     取消判断
     * @return 成功加载的行数（被取消时返回 0，且数据不提交）
     */
    public static long copyIn(Connection conn, String copySql, InputStream data,
                              LongConsumer bytesConsumer, BooleanSupplier cancelled)
            throws SQLException, IOException {
        CopyManager manager = conn.unwrap(BaseConnection.class).getCopyAPI();
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
