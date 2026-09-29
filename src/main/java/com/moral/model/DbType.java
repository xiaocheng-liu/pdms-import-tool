package com.moral.model;

/**
 * 支持的数据库类型。
 */
public enum DbType {
    ORACLE("Oracle", 1521, "oracle.jdbc.OracleDriver", "服务名/SID"),
    POSTGRESQL("PostgreSQL", 5432, "org.postgresql.Driver", "数据库名"),
    DAMENG("达梦 DM", 5236, "dm.jdbc.driver.DmDriver", "数据库名"),
    KINGBASE("人大金仓 KingbaseES", 54321, "com.kingbase8.Driver", "数据库名"),
    MYSQL("MySQL", 3306, "com.mysql.cj.jdbc.Driver", "数据库名");

    private final String displayName;
    private final int defaultPort;
    private final String driverClass;
    private final String databaseLabel;

    DbType(String displayName, int defaultPort, String driverClass, String databaseLabel) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.driverClass = driverClass;
        this.databaseLabel = databaseLabel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getDriverClass() {
        return driverClass;
    }

    /** "服务名/SID" 或 "数据库名"，用于界面标签提示 */
    public String getDatabaseLabel() {
        return databaseLabel;
    }

    /** 按枚举名解析，解析失败返回 Oracle */
    public static DbType of(String name) {
        if (name == null) {
            return ORACLE;
        }
        for (DbType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return ORACLE;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
