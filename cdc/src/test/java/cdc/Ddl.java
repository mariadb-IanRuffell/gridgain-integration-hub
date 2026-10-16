package cdc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal parser for the CREATE TABLE statements in the GridGain schema resources (table -> columns / primary key). */
final class Ddl {
    final Map<String, Set<String>> columns = new LinkedHashMap<>();
    final Map<String, List<String>> primaryKey = new LinkedHashMap<>();
    final Map<String, String> options = new LinkedHashMap<>();

    static Ddl load(String resource) throws IOException {
        String sql = SybaseCdc.stripComments(SybaseCdc.readResource(resource));
        Ddl d = new Ddl();
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\n?\\) WITH \"([^\"]*)\"", Pattern.DOTALL).matcher(sql);
        while (m.find()) {
            String table = m.group(1);
            Set<String> cols = new LinkedHashSet<>();
            List<String> pk = new ArrayList<>();
            for (String item : splitTopLevel(m.group(2))) {
                item = item.trim();
                if (item.toUpperCase().startsWith("PRIMARY KEY")) {
                    for (String c : item.substring(item.indexOf('(') + 1, item.lastIndexOf(')')).split(",")) pk.add(c.trim());
                    continue;
                }
                String[] parts = item.split("\\s+");
                cols.add(parts[0]);
                if (item.toUpperCase().contains("PRIMARY KEY")) pk.add(parts[0]);
            }
            d.columns.put(table, cols);
            d.primaryKey.put(table, pk);
            d.options.put(table, m.group(3));
        }
        return d;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }
}
