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
    private static final Pattern INTEGER = Pattern.compile("^-?\\d{1,18}$");
    private static final Pattern DECIMAL = Pattern.compile("^-?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?$");
    /** 超过该长度按大字段处理（CLOB/TEXT） */
    private static final int TEXT_THRESHOLD = 4000;

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
                int length = value.length();
                if (length > profiles.get(i).getMaxLength()) {
                    profiles.get(i).setMaxLength(length);
                }
                if (!INTEGER.matcher(value).matches()) {
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
                    profile.setType(InferredType.DOUBLE);
                } else if (profile.getMaxLength() > TEXT_THRESHOLD) {
                    profile.setType(InferredType.TEXT);
                } else {
                    profile.setType(InferredType.STRING);
                }
            }
        }
    }
}
