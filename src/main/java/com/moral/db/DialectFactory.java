package com.moral.db;

import com.moral.model.DbType;

import java.util.HashMap;
import java.util.Map;

/**
 * 方言工厂。
 */
public final class DialectFactory {

    private static final Map<DbType, Dialect> DIALECTS = new HashMap<>();

    static {
        DIALECTS.put(DbType.ORACLE, new OracleDialect());
        DIALECTS.put(DbType.POSTGRESQL, new PgDialect());
        DIALECTS.put(DbType.DAMENG, new DamengDialect());
    }

    private DialectFactory() {
    }

    public static Dialect get(DbType type) {
        Dialect dialect = DIALECTS.get(type == null ? DbType.ORACLE : type);
        return dialect == null ? DIALECTS.get(DbType.ORACLE) : dialect;
    }
}
