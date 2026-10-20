package com.moral.task;

import com.moral.model.ImportResult;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/** 失败明细必须能无损读取长字段及控制字符。 */
public class FailureRecorderTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** 落盘与手动导出保留相同的完整原始记录。 */
    @Test
    public void diskAndExportPreserveFullCsvRecord() throws Exception {
        String raw = "\"head\tline\r\n" + "x".repeat(5000) + "\"";
        File log = temporary.newFile("failures.tsv");
        File export = temporary.newFile("export.tsv");
        try (FailureRecorder recorder = new FailureRecorder()) {
            recorder.openSink(log);
            recorder.record("sample", 2, "22000", "bad row", raw);
            recorder.export(export);
        }
        for (File file : List.of(log, export)) {
            try (CSVParser parser = CSVFormat.TDF.parse(Files.newBufferedReader(file.toPath(),
                    StandardCharsets.UTF_8))) {
                assertEquals(raw, parser.getRecords().get(1).get(4));
            }
        }
    }

    /** 单条大记录应完整落盘，但不能撑大内存保留区。 */
    @Test
    public void oversizedRecordIsNotRetainedInMemory() throws Exception {
        File file = temporary.newFile("large.tsv");
        try (FailureRecorder recorder = new FailureRecorder()) {
            recorder.openSink(file);
            recorder.record("sample", 2, "22000", "bad row", "x".repeat(17 * 1024 * 1024));
            assertEquals(1, recorder.getTotalCount());
            assertTrue(recorder.snapshot().isEmpty());
        }
        assertTrue(file.length() > 17 * 1024 * 1024);
    }

    /** 内存缓存不完整时仍导出所有落盘记录，同路径导出不会截断日志。 */
    @Test
    public void resultExportUsesCompleteLog() throws Exception {
        File log = temporary.newFile("complete.tsv");
        File target = temporary.newFile("copy.tsv");
        ImportResult result = new ImportResult();
        try (FailureRecorder recorder = new FailureRecorder()) {
            recorder.openSink(log);
            recorder.record("sample", 2, "22000", "bad row", "x".repeat(17 * 1024 * 1024));
            recorder.record("sample", 3, "22000", "bad row", "small");
            result.getFailures().addAll(recorder.snapshot());
            result.setFailureRecordCount(recorder.getTotalCount());
            result.setFailureLogFile(log.getAbsolutePath());
        }
        assertEquals(1, result.getFailures().size());
        assertEquals(2, FailureRecorder.exportResult(result, target));
        assertEquals(-1, Files.mismatch(log.toPath(), target.toPath()));
        assertEquals(2, FailureRecorder.exportResult(result, log));
        assertEquals(-1, Files.mismatch(log.toPath(), target.toPath()));
        Files.delete(log.toPath());
        java.io.IOException missing = assertThrows(java.io.IOException.class,
                () -> FailureRecorder.exportResult(result, target));
        assertTrue(missing.getMessage().contains("完整失败日志不存在"));
    }

    /** 没有磁盘副本且内存超限时必须显式失败。 */
    @Test
    public void missingSinkCannotSilentlyDiscardOversizedRecord() {
        try (FailureRecorder recorder = new FailureRecorder()) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> recorder.record(
                    "sample", 2, "22000", "bad row", "x".repeat(17 * 1024 * 1024)));
            assertTrue(error.getMessage().contains("失败明细未能保存"));
        }
    }
}
