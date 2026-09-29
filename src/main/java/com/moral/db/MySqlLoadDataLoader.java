package com.moral.db;

import com.mysql.cj.jdbc.JdbcStatement;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * MySQL LOAD DATA LOCAL INFILE 批量加载：把 CSV 字节流交给服务端解析，是 MySQL 上最快的导入方式。
 *
 * <p>驱动 API（Connector/J 8.x）：{@code JdbcStatement#setLocalInfileInputStream(InputStream)}，
 * 设置后执行 {@code LOAD DATA LOCAL INFILE '...'} 时驱动从该流取数据，不再读本地文件。
 *
 * <p>本类单独隔离，仅 import com.mysql.cj.*，不使用 MySQL 时不会触发这些类的加载。
 */
public final class MySqlLoadDataLoader {

    /** 进度汇报粒度：累计这么多字节回调一次，避免逐字节回调带来的装箱开销 */
    private static final long REPORT_CHUNK = 64 * 1024L;

    private MySqlLoadDataLoader() {
    }

    /**
     * 执行 LOAD DATA LOCAL INFILE。
     *
     * @param conn          MySQL 连接（由调用方负责提交/回滚）
     * @param sql           LOAD DATA 语句
     * @param data          CSV 字节流（UTF-8）
     * @param bytesConsumer 已读取字节数回调，用于进度展示
     * @param cancelled     取消判断
     * @param statementHook 回抛执行中的 Statement，供调用方在超时时 cancel
     * @return 加载行数（驱动未返回行数时返回 0）
     */
    public static long loadLocalInfile(Connection conn, String sql, InputStream data,
                                       LongConsumer bytesConsumer, BooleanSupplier cancelled,
                                       Consumer<Statement> statementHook)
            throws SQLException, IOException {
        MonitoredStream stream = new MonitoredStream(data, bytesConsumer, cancelled);
        try (Statement statement = conn.createStatement()) {
            if (statementHook != null) {
                statementHook.accept(statement);
            }
            ((JdbcStatement) statement).setLocalInfileInputStream(stream);
            statement.execute(sql);
            long rows = statement.getLargeUpdateCount();
            return rows < 0 ? 0L : rows;
        }
    }

    /**
     * 带三件事的包装流：① 取消时立刻中断读取，让驱动中止 LOAD DATA；
     * ② 累计已读字节数驱动进度；③ 把 CRLF 归一成 LF，避免行尾 \r 落到最后一个字段里。
     */
    private static final class MonitoredStream extends FilterInputStream {

        private final LongConsumer bytesConsumer;
        private final BooleanSupplier cancelled;
        /** 已读取但尚未输出的字节（-1 表示无暂存） */
        private int pending = -1;
        private long total;
        private long reported;

        MonitoredStream(InputStream source, LongConsumer bytesConsumer, BooleanSupplier cancelled) {
            super(source instanceof BufferedInputStream ? source : new BufferedInputStream(source, 64 * 1024));
            this.bytesConsumer = bytesConsumer;
            this.cancelled = cancelled;
        }

        @Override
        public int read() throws IOException {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("导入已被取消");
            }
            int value;
            if (pending >= 0) {
                value = pending;
                pending = -1;
            } else {
                value = super.read();
                if (value == '\r') {
                    int next = super.read();
                    if (next == '\n') {
                        value = '\n';
                    } else {
                        pending = next;
                    }
                }
            }
            if (value >= 0) {
                total++;
                if (bytesConsumer != null && total - reported >= REPORT_CHUNK) {
                    bytesConsumer.accept(total - reported);
                    reported = total;
                }
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length <= 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            buffer[offset] = (byte) first;
            int count = 1;
            while (count < length) {
                int value = read();
                if (value < 0) {
                    break;
                }
                buffer[offset + count] = (byte) value;
                count++;
            }
            return count;
        }

        @Override
        public long skip(long n) throws IOException {
            // LOAD DATA 由驱动顺序读取，跳过语义不使用，避免破坏字节统计
            return 0;
        }

        @Override
        public void close() throws IOException {
            // 把最后不足 REPORT_CHUNK 的尾字节 flush 到进度回调，避免成功时进度条缺 1% 以内
            if (bytesConsumer != null && total > reported) {
                bytesConsumer.accept(total - reported);
                reported = total;
            }
            super.close();
        }
    }
}
