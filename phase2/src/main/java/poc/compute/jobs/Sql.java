package poc.compute.jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.FieldsQueryCursor;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/** Tiny SQL helpers for jobs. All tables live in schema PUBLIC; any cache handle can run the query. */
final class Sql {
    private Sql() {}

    private static SqlFieldsQuery query(String sql, boolean local, Object... args) {
        return new SqlFieldsQuery(sql).setSchema("PUBLIC").setLocal(local).setArgs(args);
    }

    /** Rows as column-name -> value maps (names lower-cased, so alias every expression). */
    static List<LinkedHashMap<String, Object>> rows(Ignite ignite, boolean local, String sql, Object... args) {
        IgniteCache<Object, Object> cache = ignite.cache("customer");
        try (FieldsQueryCursor<List<?>> cur = cache.query(query(sql, local, args))) {
            int n = cur.getColumnsCount();
            List<LinkedHashMap<String, Object>> out = new ArrayList<>();
            for (List<?> r : cur) {
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) m.put(cur.getFieldName(i).toLowerCase(), r.get(i));
                out.add(m);
            }
            return out;
        }
    }

    static List<LinkedHashMap<String, Object>> rows(Ignite ignite, String sql, Object... args) {
        return rows(ignite, false, sql, args);
    }

    static Object scalar(Ignite ignite, String sql, Object... args) {
        IgniteCache<Object, Object> cache = ignite.cache("customer");
        List<List<?>> all = cache.query(query(sql, false, args)).getAll();
        return all.isEmpty() ? null : all.get(0).get(0);
    }

    static void exec(Ignite ignite, String sql, Object... args) {
        IgniteCache<Object, Object> cache = ignite.cache("customer");
        cache.query(query(sql, false, args)).getAll();
    }
}
