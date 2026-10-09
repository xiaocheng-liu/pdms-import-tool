package com.moral.task;

import com.moral.model.FailureRecord;
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

    /** 没有磁盘副本且内存超限时必须显式失败。 */
    @Test
    public void missingSinkCannotSilentlyDiscardOversizedRecord() {
        FailureRecorder recorder = new FailureRecorder();
        assertThrows(IllegalStateException.class, () -> recorder.record(
                "sample", 2, "22000", "bad row", "x".repeat(17 * 1024 * 1024)));
    }
}
