package com.moral.db;

import org.junit.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Oracle/达梦表存在性必须与实际写入目标一致。 */
public class OracleTableExistsTest {
    /** 其他 schema、通配符命中和引用小写表都不能被误认作目标表。 */
    @Test public void rejectsOtherSchemasAndSimilarNames() throws Exception {
        for (Dialect dialect : List.of(new OracleDialect(), new DamengDialect())) {
            List<Object[]> calls = new ArrayList<>();
            Connection conn = connection(List.of(new String[]{"OTHER", "DRUG_CLASSIFY"},
                    new String[]{"KBE", "DRUGXCLASSIFY"}, new String[]{"KBE", "drug_classify"}), calls);
            assertFalse(dialect.tableExists(conn, "KBE", "drug_classify"));
            assertEquals(1, calls.size());
            assertEquals("KBE", calls.getFirst()[1]);
            assertEquals("DRUG\\_CLASSIFY", calls.getFirst()[2]);
        }
    }

    /** 遍历到精确命中的对象后才确认存在，不能只看第一条结果。 */
    @Test public void acceptsExactTarget() throws Exception {
        Connection conn = connection(List.of(new String[]{"OTHER", "SYS_CONFIG"},
                new String[]{"KBE", "SYS_CONFIG"}), new ArrayList<>());
        assertTrue(new DamengDialect().tableExists(conn, " kbe ", "sys_config"));
    }

    /** 未配置 schema 时只使用连接当前 schema，不查所有 schema。 */
    @Test public void resolvesCurrentSchema() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        Connection conn = connection(List.<String[]>of(new String[]{"CURRENT", "SYS_CONFIG"}), calls);
        assertTrue(new OracleDialect().tableExists(conn, "", "sys_config"));
        assertEquals("CURRENT", calls.getFirst()[1]);
    }

    /** 构造故意忽略元数据过滤条件的驱动，验证客户端精确校验。 */
    private static Connection connection(List<String[]> rows, List<Object[]> calls) {
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (method, args) -> {
            if (method.equals("getSearchStringEscape")) return "\\";
            if (method.equals("getUserName")) return "LOGIN";
            if (method.equals("getTables")) {
                calls.add(args);
                int[] index = {-1};
                return proxy(ResultSet.class, (name, values) -> switch (name) {
                    case "next" -> ++index[0] < rows.size();
                    case "getString" -> rows.get(index[0])[values[0].equals("TABLE_SCHEM") ? 0 : 1];
                    default -> null;
                });
            }
            return null;
        });
        return proxy(Connection.class, (method, args) -> switch (method) {
            case "getMetaData" -> metadata;
            case "getSchema" -> "CURRENT";
            default -> null;
        });
    }

    /** 仅模拟本测试所需的 JDBC 方法。 */
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (obj, method, args) -> call.invoke(method.getName(), args)));
    }

    @FunctionalInterface private interface Call {
        Object invoke(String method, Object[] args) throws Throwable;
    }
}
