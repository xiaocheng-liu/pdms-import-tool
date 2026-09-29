package com.moral.model;

/**
 * 一条导入失败明细。
 */
public class FailureRecord {

    private final String table;
    private final long lineNo;
    private final String errorCode;
    private final String message;
    private final String rawLine;

    public FailureRecord(String table, long lineNo, String errorCode, String message, String rawLine) {
        this.table = table;
        this.lineNo = lineNo;
        this.errorCode = errorCode;
        this.message = message;
        this.rawLine = rawLine;
    }

    public String getTable() {
        return table;
    }

    public long getLineNo() {
        return lineNo;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public String getRawLine() {
        return rawLine;
    }

    /** 导出为单行文本（制表符分隔） */
    public String toLine() {
        return table + "\t" + lineNo + "\t" + errorCode + "\t"
                + oneLine(message, 500) + "\t" + oneLine(rawLine, 2000);
    }

    private static String oneLine(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String value = text.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }
}
