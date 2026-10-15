package com.moral.db;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import static org.junit.Assert.assertEquals;

/** MySQL 加载流的记录换行与字段内容保真回归测试。 */
public class MySqlLoadDataLoaderTest {

    /** 只转换记录结束符，字段内部的 CRLF、CR、LF 均原样保留。 */
    @Test
    public void preservesQuotedLineBreaks() throws Exception {
        assertTransformed("ID,TEXT\r\n1,\"甲\r\n乙\r丙\n丁\"\r\n2,普通文本\r\n",
                "ID,TEXT\n1,\"甲\r\n乙\r丙\n丁\"\n2,普通文本\n");
    }

    /** 连续双引号转义和空字段不能破坏后续字段的引号状态。 */
    @Test
    public void handlesEscapedQuotesAndEmptyFields() throws Exception {
        assertTransformed("1,\"甲\"\"\r\n乙\",\"\"\r\n2,\"\"\"\r\n\"\"\",末列\r\n",
                "1,\"甲\"\"\r\n乙\",\"\"\n2,\"\"\"\r\n\"\"\",末列\n");
    }

    /** 预读暂存字节也要参与状态处理，尾部裸 CR 不应丢失。 */
    @Test
    public void processesPendingBytesAndTrailingCarriageReturn() throws Exception {
        assertTransformed("1,\r\r\n2,\r\"甲\r\n乙\"\r\n3,尾部\r",
                "1,\r\n2,\r\"甲\r\n乙\"\n3,尾部\r");
    }

    /** 字段内部 CRLF 跨过底层缓冲区边界时仍保持原样。 */
    @Test
    public void preservesLineBreakAcrossBufferBoundary() throws Exception {
        String prefix = "1,\"" + "a".repeat(65532);
        assertTransformed(prefix + "\r\nb\"\r\n2,\"无行尾\"",
                prefix + "\r\nb\"\n2,\"无行尾\"");
    }

    /** 分别验证单字节读取和不同大小的批量读取结果。 */
    private static void assertTransformed(String source, String expected) throws Exception {
        for (int bufferSize : new int[]{0, 1, 7, 4096}) {
            try (InputStream stream = openStream(source)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (bufferSize == 0) {
                    int value;
                    while ((value = stream.read()) >= 0) {
                        output.write(value);
                    }
                } else {
                    byte[] buffer = new byte[bufferSize + 2];
                    int count;
                    while ((count = stream.read(buffer, 1, bufferSize)) >= 0) {
                        output.write(buffer, 1, count);
                    }
                }
                assertEquals("读取缓冲区大小：" + bufferSize, expected,
                        output.toString(StandardCharsets.UTF_8));
            }
        }
    }

    /** 直接验证交给驱动的包装流，不建立数据库连接。 */
    private static InputStream openStream(String source) throws Exception {
        var constructor = Class.forName("com.moral.db.MySqlLoadDataLoader$MonitoredStream")
                .getDeclaredConstructor(InputStream.class, LongConsumer.class, BooleanSupplier.class);
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), null, null);
    }
}
