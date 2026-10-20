package com.moral.csv;

import com.moral.model.ColumnProfile;
import com.moral.model.InferredType;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 采样推断列类型：整数 / 小数 / 日期 / 日期时间 / 长文本 / 字符串。
 */
public final class TypeInferencer {

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DATE_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$");
    private static final Pattern INTEGER = Pattern.compile("^-?(0|[1-9]\\d{0,18})$");
    private static final Pattern DECIMAL = Pattern.compile("^-?((0|[1-9]\\d*)(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?$");
    /** 超过该长度按大字段处理（CLOB/TEXT） */
    private static final int TEXT_THRESHOLD = 4000;
    /** 字符列的最小宽度：采样再准也只是抽样，太窄的列（如 varchar(2)）几乎必然超长 */
    private static final int MIN_STRING_LENGTH = 64;
    /** long 边界文本：用于在不解析、不抛异常的前提下判断整数是否溢出 */
    private static final String LONG_MAX_TEXT = "9223372036854775807";
    private static final String LONG_MIN_TEXT = "-9223372036854775808";

    private TypeInferencer() {
    }

    public static Inferencer create(List<ColumnProfile> profiles) {
        return new Inferencer(profiles);
    }

    /**
     * 逐行累积采样状态。
     */
    public static final class Inferencer {

        private final List<ColumnProfile> profiles;
        private final int size;
        private final boolean[] allInteger;
        private final boolean[] allDecimal;
        private final boolean[] allDate;
        private final boolean[] allDateTime;
        private final boolean[] hasValue;

        private Inferencer(List<ColumnProfile> profiles) {
            this.profiles = profiles;
            this.size = profiles.size();
            this.allInteger = new boolean[size];
            this.allDecimal = new boolean[size];
            this.allDate = new boolean[size];
            this.allDateTime = new boolean[size];
            this.hasValue = new boolean[size];
            for (int i = 0; i < size; i++) {
                allInteger[i] = true;
                allDecimal[i] = true;
                allDate[i] = true;
                allDateTime[i] = true;
            }
        }

        public void accept(List<String> values) {
            for (int i = 0; i < size; i++) {
                String value = i < values.size() ? values.get(i) : "";
                if (value == null || value.isEmpty()) {
                    continue;
                }
                hasValue[i] = true;
                profiles.get(i).observeCharacters(value);
                int length = value.length();
                if (length > profiles.get(i).getMaxLength()) {
                    profiles.get(i).setMaxLength(length);
                }
                if (!INTEGER.matcher(value).matches()) {
                    allInteger[i] = false;
                } else if (allInteger[i] && !fitsLong(value)) {
                    // 正则已保证是规范整数串，这里只需判断是否超出 long 取值范围
                    allInteger[i] = false;
                }
                if (!DECIMAL.matcher(value).matches()) {
                    allDecimal[i] = false;
                }
                if (!DATE.matcher(value).matches()) {
                    allDate[i] = false;
                }
                if (!DATE_TIME.matcher(value).matches()) {
                    allDateTime[i] = false;
                }
            }
        }

        /**
         * 判断规范整数串能否放入 long：位数不足边界长度必然可容纳，等长时按字典序比较。
         *
         * <p>相比 {@code Long.parseLong} 捕获异常，这里无解析、无临时对象、无异常开销。
         * 负号后等长数字串的字典序与数值序相反（{@code -...809 < -...808}），故负数取 {@code <=}。
         */
        private static boolean fitsLong(String value) {
            if (value.charAt(0) == '-') {
                return value.length() < LONG_MIN_TEXT.length() || value.compareTo(LONG_MIN_TEXT) <= 0;
            }
            return value.length() < LONG_MAX_TEXT.length() || value.compareTo(LONG_MAX_TEXT) <= 0;
        }

        /** 采样结束后确定最终类型 */
        public void finish() {
            for (int i = 0; i < size; i++) {
                ColumnProfile profile = profiles.get(i);
                if (!hasValue[i]) {
                    profile.setType(InferredType.STRING);
                    profile.setMaxLength(Math.max(profile.getMaxLength(), 1));
                    continue;
                }
                if (allInteger[i]) {
                    profile.setType(InferredType.LONG);
                } else if (allDateTime[i]) {
                    profile.setType(InferredType.DATETIME);
                } else if (allDate[i]) {
                    profile.setType(InferredType.DATE);
                } else if (allDecimal[i]) {
                    profile.setType(InferredType.DECIMAL);
                } else if (profile.getMaxLength() > TEXT_THRESHOLD
                        || profile.getMaxLength() * 2 > TEXT_THRESHOLD) {
                    // 采样最大值已接近 VARCHAR 上限（列宽按 1.5 倍放宽），后面再出现更长的取值必然超长，
                    // 直接按大字段建列，避免 varchar(3972) 之类被后续数据撑爆
                    profile.setType(InferredType.TEXT);
                } else {
                    profile.setType(InferredType.STRING);
                }
            }
            // 采样只覆盖前若干行，字符列长度过窄极易触发 22001（值超长），给一个最小宽度兜底
            for (ColumnProfile profile : profiles) {
                if (profile.getType() == InferredType.STRING && profile.getMaxLength() < MIN_STRING_LENGTH) {
                    profile.setMaxLength(MIN_STRING_LENGTH);
                }
            }
        }
    }
}
