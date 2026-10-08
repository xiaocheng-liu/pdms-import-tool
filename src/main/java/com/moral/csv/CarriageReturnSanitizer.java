package com.moral.csv;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 清洗"未加引号的回车符"的字节流包装。
 *
 * <p>PostgreSQL 的 {@code COPY ... WITH (FORMAT csv)} 严格按 RFC4180 解析，
 * 未被引号包裹的 {@code \r} 会直接报错 "unquoted carriage return found in data"；
 * commons-csv 同样会把裸 {@code \r} 当作行结束符，从而把一行拆成两行、字段错位。
 *
 * <p>本流按引号状态跟踪（支持 {@code ""} 转义），只对<b>引号外</b>的裸 CR 做替换：
 * <ul>
 *   <li>{@code \r\n}：属于正常 CRLF 行尾，原样保留；</li>
 *   <li>引号内的 {@code \r}：合法的多行字段内容，原样保留；</li>
 *   <li>引号外的裸 {@code \r}：替换为空格，保证该行仍是完整的一行（不丢后半段字段）。</li>
 * </ul>
 *
 * <p>只处理 ASCII 控制字符，UTF-8 / GBK 的多字节序列都不会包含 0x0D，按字节扫描是安全的。
 */
public final class CarriageReturnSanitizer extends FilterInputStream {

    /** 裸 CR 的替换字符：用空格而不是删除，避免把前后两个词粘连成一个词 */
    private static final int REPLACEMENT = ' ';

    private boolean inQuotes;
    /** 已读但尚未输出的字节（-1 表示无暂存） */
    private int pending = -1;
    private long replaced;

    public CarriageReturnSanitizer(InputStream in) {
        super(in);
    }

    /** 被替换掉的裸回车符数量（供日志提示） */
    public long getReplacedCount() {
        return replaced;
    }

    @Override
    public int read() throws IOException {
        if (pending >= 0) {
            int value = pending;
            pending = -1;
            return value;
        }
        int value = super.read();
        if (value < 0) {
            return -1;
        }
        return transform(value);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        int count = 0;
        while (count < length) {
            int value = read();
            if (value < 0) {
                return count == 0 ? -1 : count;
            }
            buffer[offset + count] = (byte) value;
            count++;
        }
        return count;
    }

    @Override
    public long skip(long n) throws IOException {
        // 跳过会绕过清洗逻辑，改为逐字节读取丢弃，保证状态机与数据一致
        long skipped = 0;
        while (skipped < n) {
            if (read() < 0) {
                break;
            }
            skipped++;
        }
        return skipped;
    }

    private int transform(int value) throws IOException {
        if (value == '"') {
            inQuotes = !inQuotes;
            return value;
        }
        if (value != '\r' || inQuotes) {
            return value;
        }
        int next = super.read();
        if (next >= 0) {
            // 后跟 LF 属于正常 CRLF 行尾；其它字节（含 EOF）都说明这是一个裸 CR
            pending = next;
        }
        if (next == '\n') {
            return value;
        }
        replaced++;
        return REPLACEMENT;
    }
}
