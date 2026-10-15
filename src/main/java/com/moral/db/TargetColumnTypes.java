package com.moral.db;

import com.moral.csv.CsvMeta;
import com.moral.model.InferredType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 按实际写入目标读取列类型，避免 CSV 采样改变参数绑定方式。 */
public final class TargetColumnTypes {

    private TargetColumnTypes() {
    }

    /** 只查询列元数据；缺列、无权限或不支持的类型在清表前报错。 */
    public static void apply(Connection connection, Dialect dialect, String schema,
                             String table, CsvMeta meta, boolean newlyCreated) throws SQLException {
        String columns = meta.getColumns().stream()
                .map(dialect::normalizeTableName).map(dialect::quote)
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + columns + " FROM " + dialect.fullyQualified(schema, table) + " WHERE 1=0";
        List<InferredType> bindings = new ArrayList<>();
        List<String> declarations = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata.getColumnCount() != meta.getProfiles().size()) {
                throw new SQLException("目标表列元数据与 CSV 表头数量不一致：" + table);
            }
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                int jdbcType = metadata.getColumnType(i);
                String typeName = metadata.getColumnTypeName(i);
                InferredType binding = bindingType(jdbcType);
                if (binding == null) {
                    throw new SQLException("目标列 " + table + "." + meta.getColumns().get(i - 1)
                            + " 暂不支持绑定类型 " + typeName + "（JDBC " + jdbcType + "）");
                }
                bindings.add(binding);
                declarations.add(typeName == null || typeName.isBlank() ? binding.name() : typeName);
            }
        }
        // 新表仅靠采样得出的数值列仍保留前导零冲突检查，避免自动建表后静默丢零。
        for (int i = 0; i < bindings.size(); i++) {
            meta.getProfiles().get(i).setType(bindings.get(i));
            if (!newlyCreated || meta.getProfiles().get(i).getDeclaredSqlType() != null) {
                meta.getProfiles().get(i).setDeclaredSqlType(declarations.get(i));
            }
        }
    }

    /** 数值统一用 BigDecimal，避免 BIGINT 无符号值或 NUMBER 超出 Long 范围。 */
    private static InferredType bindingType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> InferredType.DECIMAL;
            case Types.DATE, Types.TIMESTAMP -> InferredType.DATETIME;
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> InferredType.STRING;
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> InferredType.TEXT;
            default -> null;
        };
    }
}
