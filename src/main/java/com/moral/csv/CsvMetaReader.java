package com.moral.csv;

import com.moral.model.ColumnProfile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取 CSV 表头并对前 N 行采样，推断每列类型。
 * 支持 UTF-8 BOM 自动跳过。
 */
public final class CsvMetaReader {

    /**
     * 默认采样行数。
     *
     * <p>采样用于推断列类型与列宽：样本太少时，靠后的大值/特殊格式不会被看到，
     * 建表列宽偏小就会大量出现 22001（值超长）。这里放大到 5000 行以提高代表性。
     */
    public static final int DEFAULT_SAMPLE_ROWS = 5000;

    private CsvMetaReader() {
    }

    public static CsvMeta read(File file, Charset charset) throws IOException {
        return read(file, charset, DEFAULT_SAMPLE_ROWS);
    }

    public static CsvMeta read(File file, Charset charset, int sampleRows) throws IOException {
        List<String> columns = new ArrayList<>();
        List<ColumnProfile> profiles = new ArrayList<>();
        try (InputStream raw = openWithoutBom(file);
             Reader reader = new InputStreamReader(new BufferedInputStream(raw, 1 << 16), charset == null ? StandardCharsets.UTF_8 : charset);
             CSVParser parser = csvFormat().parse(reader)) {

            TypeInferencer.Inferencer inferencer = null;
            int rows = 0;
            for (CSVRecord record : parser) {
                if (columns.isEmpty()) {
                    for (int i = 0; i < record.size(); i++) {
                        String name = record.get(i);
                        if (name == null || name.trim().isEmpty()) {
                            name = "COL_" + (i + 1);
                        }
                        columns.add(name.trim());
                        profiles.add(new ColumnProfile(name.trim()));
                    }
                    inferencer = TypeInferencer.create(profiles);
                    continue;
                }
                inferencer.accept(valuesOf(record, columns.size()));
                rows++;
                if (rows >= sampleRows) {
                    break;
                }
            }
            if (inferencer != null) {
                inferencer.finish();
            }
        }
        return new CsvMeta(columns, profiles);
    }

    /** 跳过 UTF-8/UTF-16 BOM，避免首列名带乱码 */
    public static InputStream openWithoutBom(File file) throws IOException {
        InputStream in = Files.newInputStream(file.toPath());
        PushbackInputStream pushback = new PushbackInputStream(new BufferedInputStream(in, 8), 4);
        byte[] bom = new byte[4];
        int read = pushback.read(bom);
        if (read <= 0) {
            return pushback;
        }
        int skip = 0;
        if (read >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
            skip = 3;
        } else if (read >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
            skip = 2;
        } else if (read >= 2 && (bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF) {
            skip = 2;
        }
        if (skip > 0) {
            pushback.unread(bom, skip, read - skip);
        } else {
            pushback.unread(bom, 0, read);
        }
        return pushback;
    }

    /** 统一的 CSV 格式：标准逗号分隔、双引号转义、忽略空行 */
    public static CSVFormat csvFormat() {
        return CSVFormat.DEFAULT
                .withIgnoreEmptyLines(true)
                .withIgnoreSurroundingSpaces(false)
                .withTrim(false)
                .withAllowDuplicateHeaderNames(true);
    }

    /** 取记录的前 n 个值，不足补空串 */
    public static List<String> valuesOf(CSVRecord record, int size) {
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String value = i < record.size() ? record.get(i) : "";
            values.add(value == null ? "" : value);
        }
        return values;
    }
}
