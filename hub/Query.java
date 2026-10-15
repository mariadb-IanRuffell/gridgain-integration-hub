import java.sql.*;
import java.nio.file.*;
import java.util.*;

/** Runs every statement in queries.sql (or the file given as the first argument) against GridGain and prints the rows. */
public class Query {
    public static void main(String[] a) throws Exception {
        String file = a.length > 0 ? a[0] : "queries.sql";
        String url = System.getenv().getOrDefault("GG_JDBC", "jdbc:ignite:thin://127.0.0.1:10800,127.0.0.1:10801,127.0.0.1:10802");
        StringBuilder cur = new StringBuilder(), title = new StringBuilder();
        List<String[]> stmts = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            if (line.startsWith("--")) { if (line.matches("-- \\d+\\..*")) title.setLength(0); if (line.matches("-- \\d+\\..*")) title.append(line.substring(3)); continue; }
            if (line.isBlank() && cur.length() == 0) continue;
            cur.append(line).append('\n');
            if (line.trim().endsWith(";")) { stmts.add(new String[] {title.toString(), cur.toString().replaceAll(";\\s*$", "")}); cur.setLength(0); }
        }
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            for (String[] st : stmts) {
                System.out.println("\n== " + st[0]);
                long t0 = System.nanoTime();
                try (ResultSet rs = s.executeQuery(st[1])) {
                    ResultSetMetaData m = rs.getMetaData();
                    int n = m.getColumnCount(), rows = 0;
                    StringBuilder h = new StringBuilder();
                    for (int i = 1; i <= n; i++) h.append(String.format("%-18s", m.getColumnLabel(i).toLowerCase()));
                    System.out.println(h);
                    while (rs.next()) {
                        rows++;
                        StringBuilder b = new StringBuilder();
                        for (int i = 1; i <= n; i++) { String v = String.valueOf(rs.getObject(i)); b.append(String.format("%-18s", v.length() > 17 ? v.substring(0, 17) : v)); }
                        System.out.println(b);
                    }
                    System.out.printf("(%d rows, %d ms)%n", rows, (System.nanoTime() - t0) / 1_000_000);
                } catch (SQLException e) { System.out.println("ERROR: " + e.getMessage()); }
            }
        }
    }
}
