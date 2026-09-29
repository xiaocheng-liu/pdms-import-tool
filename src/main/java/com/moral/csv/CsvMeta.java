package com.moral.csv;

import com.moral.model.ColumnProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CSV 元数据：表头列名 + 每列的采样类型。
 */
public class CsvMeta {

    private final List<String> columns;
    private final List<ColumnProfile> profiles;

    public CsvMeta(List<String> columns, List<ColumnProfile> profiles) {
        this.columns = columns == null ? new ArrayList<>() : columns;
        this.profiles = profiles == null ? new ArrayList<>() : profiles;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<ColumnProfile> getProfiles() {
        return profiles;
    }

    public boolean isValid() {
        return !columns.isEmpty() && columns.size() == profiles.size();
    }

    public static CsvMeta empty() {
        return new CsvMeta(Collections.emptyList(), Collections.emptyList());
    }
}
