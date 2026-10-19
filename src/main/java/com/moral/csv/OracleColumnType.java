package com.moral.csv;

import com.moral.model.DbType;
import com.moral.model.InferredType;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Oracle 列类型到目标数据库的转换结果，同时约束 JDBC 参数绑定类型。 */
public record OracleColumnType(String sql, InferredType binding) {

    /** 仅转换已支持的类型；不能可靠转换时返回 null，由调用方报告无法转换。 */
    public static OracleColumnType map(String declaration, DbType target) {
        String type = declaration.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim()
                .replaceAll("\\s*([(),])\\s*", "$1").replace("- ", "-");
        boolean oracle = target == DbType.ORACLE;
        boolean dm = target == DbType.DAMENG;
        boolean mysql = target == DbType.MYSQL;
        Matcher chars = Pattern.compile("(VARCHAR2|VARCHAR|NVARCHAR2|CHAR|NCHAR)\\((\\d+)(?: (BYTE|CHAR))?\\)").matcher(type);
        if (chars.matches()) {
            int length = Integer.parseInt(chars.group(2));
            if (length < 1) return null;
            if (oracle) {
                String name = chars.group(1);
                // 国家字符类型自带字符语义，仅为普通字符列补充缺省单位。
                String sql = chars.group(3) == null && !name.startsWith("N")
                        ? name + "(" + length + " CHAR)" : type;
                return new OracleColumnType(sql, InferredType.STRING);
            }
            boolean fixed = chars.group(1).equals("CHAR") || chars.group(1).equals("NCHAR");
            if ((mysql && length > (fixed ? 255 : 4000)) || (dm && length > 2047)) {
                return new OracleColumnType(mysql ? "LONGTEXT" : "CLOB", InferredType.TEXT);
            }
            if (dm || target == DbType.KINGBASE) {
                String unit = target == DbType.KINGBASE && "BYTE".equals(chars.group(3)) ? "BYTE" : "CHAR";
                return new OracleColumnType((fixed ? "CHAR(" : "VARCHAR(") + length + " " + unit + ")", InferredType.STRING);
            }
            return new OracleColumnType((fixed ? "CHAR(" : "VARCHAR(") + length + ")", InferredType.STRING);
        }
        Matcher number = Pattern.compile("(?:NUMBER|NUMERIC|DECIMAL|DEC)(?:\\((\\d+|\\*)(?:,(-?\\d+))?\\))?").matcher(type);
        if (number.matches()) {
            Integer precision = number.group(1) == null ? null
                    : number.group(1).equals("*") ? 38 : Integer.valueOf(number.group(1));
            int scale = number.group(2) == null ? 0 : Integer.parseInt(number.group(2));
            if ((precision != null && (precision < 1 || precision > 38)) || scale < -84 || scale > 127) return null;
            if (oracle) {
                String suffix = precision == null ? "" : "(" + precision + "," + scale + ")";
                return new OracleColumnType("NUMBER" + suffix, InferredType.DECIMAL);
            }
            if (dm) {
                if (precision == null) return new OracleColumnType("NUMBER", InferredType.DECIMAL);
                if (scale < 0 || scale > precision) return null;
                return new OracleColumnType("DECIMAL(" + precision + "," + scale + ")", InferredType.DECIMAL);
            }
            if (precision == null) {
                // MySQL 采用现有方言的最大精度默认值，仍按 BigDecimal 绑定。
                return new OracleColumnType(mysql ? "DECIMAL(65,30)" : "NUMERIC", InferredType.DECIMAL);
            }
            int targetScale = Math.max(0, scale);
            int targetPrecision = Math.max(precision, scale) + Math.max(0, -scale);
            if (mysql && (targetScale > 30 || targetPrecision > 65)) {
                return null;
            }
            return new OracleColumnType((mysql ? "DECIMAL(" : "NUMERIC(")
                    + targetPrecision + "," + targetScale + ")", InferredType.DECIMAL);
        }
        if (type.equals("INTEGER") || type.equals("INT") || type.equals("SMALLINT")) {
            return map("NUMBER(38)", target);
        }
        if (type.equals("DATE")) {
            return new OracleColumnType(oracle ? "DATE" : mysql ? "DATETIME" : "TIMESTAMP",
                    InferredType.DATETIME);
        }
        Matcher timestamp = Pattern.compile("TIMESTAMP(?:\\((\\d+)\\))?").matcher(type);
        if (timestamp.matches()) {
            int precision = timestamp.group(1) == null ? 6 : Integer.parseInt(timestamp.group(1));
            if (precision > 9) return null;
            if (!oracle && !dm && precision > 6) return null;
            return new OracleColumnType((mysql ? "DATETIME(" : "TIMESTAMP(") + precision + ")",
                    InferredType.DATETIME);
        }
        if (type.equals("CLOB") || type.equals("NCLOB") || type.equals("LONG")) {
            return new OracleColumnType(oracle ? (type.equals("NCLOB") ? "NCLOB" : "CLOB")
                    : dm ? "CLOB" : mysql ? "LONGTEXT" : "TEXT", InferredType.TEXT);
        }
        if (type.matches("FLOAT(?:\\(\\d+\\))?") || type.equals("REAL") || type.equals("DOUBLE PRECISION")) {
            return new OracleColumnType(oracle || dm ? type : mysql ? "DOUBLE" : "DOUBLE PRECISION",
                    InferredType.DECIMAL);
        }
        return null;
    }

    /** 返回字符列的声明长度；大字段和其他类型不限制采样字符数。 */
    int characterLimit() {
        Matcher chars = Pattern.compile("(?:VARCHAR2|VARCHAR|NVARCHAR2|CHAR|NCHAR)\\((\\d+)(?: (?:BYTE|CHAR))?\\)").matcher(sql);
        return chars.matches() ? Integer.parseInt(chars.group(1)) : Integer.MAX_VALUE;
    }
}
