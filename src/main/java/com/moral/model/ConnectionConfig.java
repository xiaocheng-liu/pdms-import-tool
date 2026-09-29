package com.moral.model;

/**
 * 数据库连接配置。
 */
public class ConnectionConfig {

    private DbType dbType = DbType.ORACLE;
    private String host = "127.0.0.1";
    private int port = DbType.ORACLE.getDefaultPort();
    /** Oracle 为服务名/SID，PG/达梦为数据库名 */
    private String database = "";
    private String user = "";
    private String password = "";
    /** 目标 schema，为空时使用连接用户默认 schema */
    private String schema = "KBE";

    public DbType getDbType() {
        return dbType;
    }

    public void setDbType(DbType dbType) {
        this.dbType = dbType;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    /** 校验必填项，返回中文错误描述，合法时返回 null */
    public String validate() {
        if (dbType == null) {
            return "请选择数据库类型";
        }
        if (isBlank(host)) {
            return "请填写主机地址";
        }
        if (port <= 0 || port > 65535) {
            return "端口号不合法";
        }
        if (isBlank(database)) {
            return "请填写" + dbType.getDatabaseLabel();
        }
        if (isBlank(user)) {
            return "请填写用户名";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
