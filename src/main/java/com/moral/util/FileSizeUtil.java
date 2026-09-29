package com.moral.util;

import java.text.DecimalFormat;
import java.text.NumberFormat;

/**
 * 体积、耗时、数量格式化工具。
 */
public final class FileSizeUtil {

    private static final DecimalFormat SIZE_FORMAT = new DecimalFormat("#,##0.##");
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance();

    private FileSizeUtil() {
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return SIZE_FORMAT.format(kb) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return SIZE_FORMAT.format(mb) + " MB";
        }
        return SIZE_FORMAT.format(mb / 1024.0) + " GB";
    }

    public static String formatNumber(long value) {
        return NUMBER_FORMAT.format(value);
    }

    /** 毫秒转 "1分20秒" 形式 */
    public static String formatDuration(long millis) {
        if (millis < 0) {
            return "-";
        }
        if (millis < 1000) {
            return millis + " ms";
        }
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes < 60) {
            return minutes + " 分 " + rest + " 秒";
        }
        long hours = minutes / 60;
        return hours + " 小时 " + (minutes % 60) + " 分";
    }

    /** 估算剩余时间：按已完成的字节速度推算 */
    public static String formatEta(long doneBytes, long totalBytes, long elapsedMs) {
        if (doneBytes <= 0 || elapsedMs <= 0 || totalBytes <= doneBytes) {
            return "计算中";
        }
        double speed = doneBytes / (elapsedMs / 1000.0);
        long remainSeconds = (long) ((totalBytes - doneBytes) / speed);
        return "预计剩余 " + formatDuration(remainSeconds * 1000);
    }
}
