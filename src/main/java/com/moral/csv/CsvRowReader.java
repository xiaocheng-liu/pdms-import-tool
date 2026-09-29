package com.moral.csv;

import com.moral.csv.CsvMetaReader;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * 流式读取 CSV 行，支持按字节区间读取（大表分片）。
 * 全程不把文件载入内存。
 */
public final class CsvRowReader implements Closeable {

    private final CountingInputStream counting;
    private final CSVParser parser;
    private final Iterator<CSVRecord> iterator;
    private final long rangeSize;

    private CsvRowReader(CountingInputStream counting, CSVParser parser, long rangeSize) {
        this.counting = counting;
        this.parser = parser;
        this.iterator = parser.iterator();
        this.rangeSize = rangeSize;
    }

    /**
     * 打开文件的 [start, end) 区间。
     *
     * @param skipHeader 是否跳过第一条记录（整表导入时首行为表头；非首个分片用于丢弃"半行"）
     */
    public static CsvRowReader open(File file, Charset charset, long start, long end, boolean skipHeader) throws IOException {
        FileInputStream fileStream = new FileInputStream(file);
        FileChannel channel = fileStream.getChannel();
        try {
            channel.position(start);
        } catch (IOException e) {
            fileStream.close();
            throw e;
        }
        long limit = Math.max(0, end - start);
        CountingInputStream counting = new CountingInputStream(fileStream, limit);
        Reader reader = new InputStreamReader(counting, charset == null ? StandardCharsets.UTF_8 : charset);
        CSVParser parser = CsvMetaReader.csvFormat().parse(reader);
        CsvRowReader rowReader = new CsvRowReader(counting, parser, limit);
        if (skipHeader && rowReader.iterator.hasNext()) {
            rowReader.iterator.next();
        }
        return rowReader;
    }

    /**
     * 打开文件的原始字节区间流 [start, end)，不做 CSV 解析。
     * 供 COPY 等批量加载通道直接把字节流交给数据库解析。
     */
    public static InputStream openRaw(File file, long start, long end) throws IOException {
        FileInputStream fileStream = new FileInputStream(file);
        try {
            fileStream.getChannel().position(start);
        } catch (IOException e) {
            fileStream.close();
            throw e;
        }
        return new BoundedInputStream(fileStream, Math.max(0, end - start));
    }

    /** 下一条记录，没有更多数据时返回 null */
    public CSVRecord next() {
        return iterator.hasNext() ? iterator.next() : null;
    }

    /** 已读取字节数（用于进度展示） */
    public long bytesRead() {
        return Math.min(rangeSize, counting.getCount());
    }

    /** 当前记录序号（从 1 开始，不含表头），用于失败定位 */
    public long recordNumber() {
        return parser.getRecordNumber();
    }

    @Override
    public void close() {
        try {
            parser.close();
        } catch (IOException ignore) {
            // 关闭失败忽略
        }
    }

    /** 限制读取长度的流，保证分片不会越界读取下一个分片的字节 */
    private static class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(length, remaining);
            int read = super.read(buffer, offset, toRead);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long toSkip = Math.min(n, remaining);
            long skipped = super.skip(toSkip);
            remaining -= skipped;
            return skipped;
        }
    }

    /** 统计已读字节数，供进度条使用 */
    private static final class CountingInputStream extends BoundedInputStream {
        private long count;

        CountingInputStream(InputStream in, long limit) {
            super(in, limit);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        long getCount() {
            return count;
        }
    }
}
