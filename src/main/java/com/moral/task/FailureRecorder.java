package com.moral.task;

import com.moral.model.FailureRecord;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 失败明细记录：内存保留（上限 2 万条）+ 实时写入失败日志文件 + 可回调界面。
 */
public final class FailureRecorder implements Closeable {

    /** 内存中最多保留的明细条数，避免极端情况下占用过多内存 */
    private static final int MAX_RECORDS = 20000;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final List<FailureRecord> records = Collections.synchronizedList(new ArrayList<>());
    private volatile long totalCount;
    private volatile Consumer<FailureRecord> listener;

    private BufferedWriter sink;
    private File sinkFile;
    private int sinkWrites;

    /** 失败明细实时写入的目标文件（用户主目录下 logs 目录） */
    public static File defaultLogFile() {
        String name = "failures_" + LocalDateTime.now().format(FILE_TIME) + ".tsv";
        File dir = new File(System.getProperty("user.home"), ".pdms-import-tool/logs");
        return new File(dir, name);
    }

    /** 打开实时落盘文件（写入表头） */
    public synchronized void openSink(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        this.sink = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
        this.sinkFile = file;
        this.sinkWrites = 0;
        sink.write("表名\t行号\t错误码\t错误信息\t原始数据");
        sink.newLine();
        sink.flush();
    }

    /** 关闭落盘文件并返回已写入的文件（未写入任何明细时返回 null 并删除空文件） */
    public synchronized File closeSink() {
        if (sink == null) {
            return null;
        }
        try {
            sink.flush();
            sink.close();
        } catch (IOException ignore) {
            // 关闭失败忽略
        }
        File file = sinkFile;
        sink = null;
        if (file != null && totalCount == 0) {
            // 没有失败时不留空文件
            if (file.delete()) {
                return null;
            }
        }
        return file;
    }

    /** 设置实时回调（界面日志展示用） */
    public void setListener(Consumer<FailureRecord> listener) {
        this.listener = listener;
    }

    public void record(String table, long lineNo, String errorCode, String message, String rawLine) {
        FailureRecord record = new FailureRecord(table, lineNo, errorCode, message, rawLine);
        totalCount++;
        if (records.size() < MAX_RECORDS) {
            records.add(record);
        }
        writeToSink(record);
        Consumer<FailureRecord> current = listener;
        if (current != null) {
            try {
                current.accept(record);
            } catch (RuntimeException ignore) {
                // 回调异常不影响导入
            }
        }
    }

    /**
     * 实时写入失败日志文件：每条都 flush，
     * 保证程序异常中断（强杀、断连）时已记录的坏行不会丢失。
     */
    private void writeToSink(FailureRecord record) {
        synchronized (this) {
            if (sink == null) {
                return;
            }
            try {
                sink.write(record.toLine());
                sink.newLine();
                sink.flush();
                sinkWrites++;
            } catch (IOException ignore) {
                // 日志写入失败不影响导入
            }
        }
    }

    public long getTotalCount() {
        return totalCount;
    }

    public List<FailureRecord> snapshot() {
        return new ArrayList<>(records);
    }

    /** 导出为 UTF-8 文本文件，返回导出行数 */
    public int export(File file) throws IOException {
        return export(snapshot(), file);
    }

    /** 导出指定明细列表 */
    public static int export(List<FailureRecord> records, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write("表名\t行号\t错误码\t错误信息\t原始数据");
            writer.newLine();
            for (FailureRecord record : records) {
                writer.write(record.toLine());
                writer.newLine();
            }
        }
        return records.size();
    }

    @Override
    public void close() {
        closeSink();
    }
}
