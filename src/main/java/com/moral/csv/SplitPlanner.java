package com.moral.csv;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 大表分片规划：按字节把大文件切分为多段。
 * 切分点必须落在"行首"，且该位置之前的双引号数量为偶数（避免切在被引号包裹的多行字段中间）。
 */
public final class SplitPlanner {

    /** 单个分片最小字节数，避免把大文件切得过碎 */
    public static final long MIN_SHARD_BYTES = 64L * 1024 * 1024;

    private static final int BUFFER_SIZE = 8 * 1024 * 1024;

    private SplitPlanner() {
    }

    /**
     * 规划分片。
     *
     * @param file       目标文件
     * @param maxShards  最大分片数（通常等于并发线程数）
     * @param threshold  分片阈值：小于该体积的文件不拆分
     */
    public static List<ByteRange> plan(File file, int maxShards, long threshold) throws IOException {
        long length = file.length();
        List<ByteRange> single = new ArrayList<>(1);
        if (length <= 0) {
            single.add(new ByteRange(0, Math.max(length, 0), 0, 1));
            return single;
        }
        if (length < threshold || maxShards <= 1) {
            single.add(new ByteRange(0, length, 0, 1));
            return single;
        }
        int shards = (int) Math.min(maxShards, Math.max(1, length / MIN_SHARD_BYTES));
        if (shards <= 1) {
            single.add(new ByteRange(0, length, 0, 1));
            return single;
        }

        List<Long> boundaries = findBoundaries(file, length, shards);
        List<ByteRange> ranges = new ArrayList<>(boundaries.size());
        for (int i = 0; i < boundaries.size() - 1; i++) {
            ranges.add(new ByteRange(boundaries.get(i), boundaries.get(i + 1), i, boundaries.size() - 1));
        }
        if (ranges.isEmpty()) {
            ranges.add(new ByteRange(0, length, 0, 1));
        }
        return ranges;
    }

    /**
     * 扫描文件，返回 [0, b1, b2, ..., length] 形式的边界列表。
     */
    private static List<Long> findBoundaries(File file, long length, int shards) throws IOException {
        List<Long> boundaries = new ArrayList<>(shards + 1);
        boundaries.add(0L);

        long targetIndex = 1;
        long position = 0;
        long quoteCount = 0;
        long lastSafeLineStart = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()), BUFFER_SIZE)) {
            int read;
            while ((read = in.read(buffer)) > 0 && targetIndex < shards) {
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    position++;
                    if (b == '"') {
                        quoteCount++;
                    } else if (b == '\n') {
                        if ((quoteCount & 1L) == 0L) {
                            long lineStart = position;
                            lastSafeLineStart = lineStart;
                            long target = length * targetIndex / shards;
                            if (lineStart >= target && lineStart < length) {
                                boundaries.add(lineStart);
                                targetIndex++;
                                if (targetIndex >= shards) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        // 未找到足够切分点（例如行数极少）时，用最后一个安全行首兜底
        while (boundaries.size() > 1 && boundaries.get(boundaries.size() - 1) >= length) {
            boundaries.remove(boundaries.size() - 1);
        }
        if (targetIndex < shards && lastSafeLineStart > 0 && lastSafeLineStart < length
                && lastSafeLineStart > boundaries.get(boundaries.size() - 1)) {
            boundaries.add(lastSafeLineStart);
        }
        boundaries.add(length);
        return boundaries;
    }
}
