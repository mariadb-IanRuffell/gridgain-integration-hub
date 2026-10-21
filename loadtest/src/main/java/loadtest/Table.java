package loadtest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Random;

/** The test table: its own table, so the hub tables are never touched by a load test. Partitioned, one backup, like the rest of the cluster. */
final class Table {
    static final String NAME = "loadtest_account";

    static final String DDL = "CREATE TABLE IF NOT EXISTS " + NAME + " (id BIGINT PRIMARY KEY, customer_id INT, balance DECIMAL(18,2), status VARCHAR(16), "
        + "region VARCHAR(8), payload VARCHAR(200), updated_at TIMESTAMP) "
        + "WITH \"template=partitioned,backups=1,cache_name=" + NAME + ",atomicity=transactional\"";
    static final String INDEX = "CREATE INDEX IF NOT EXISTS " + NAME + "_customer_idx ON " + NAME + " (customer_id)";

    private Table() {}

    static long count(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + NAME)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Create the table if needed and top it up to {@code rows} seeded rows (ids 1..rows). Returns the number of rows inserted. */
    static long prepare(Connection c, long rows) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(DDL);
            s.execute(INDEX);
        }
        long have;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + NAME + " WHERE id <= " + rows)) {
            rs.next();
            have = rs.getLong(1);
        }
        if (have >= rows) return 0;
        if (have > 0) try (Statement s = c.createStatement()) {      // a partial load: start clean
            s.execute("DELETE FROM " + NAME);
        }
        Random rnd = new Random(42);
        String[] status = {"ACTIVE", "ACTIVE", "ACTIVE", "DORMANT", "CLOSED"}, region = {"EU", "US", "APAC", "LATAM"};
        long inserted = 0;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + NAME + " (id, customer_id, balance, status, region, payload, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (long id = 1; id <= rows; id++) {
                ps.setLong(1, id);
                ps.setInt(2, (int) (1 + id % Math.max(1, rows / 10)));
                ps.setBigDecimal(3, java.math.BigDecimal.valueOf(rnd.nextInt(1_000_000), 2));
                ps.setString(4, status[rnd.nextInt(status.length)]);
                ps.setString(5, region[rnd.nextInt(region.length)]);
                ps.setString(6, "seed-" + id + "-" + "x".repeat(100));
                ps.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
                ps.addBatch();
                if (++inserted % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        return inserted;
    }

    /** Remove the rows a run inserted (ids above the seeded range). */
    static void removeInserted(Connection c, long rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + NAME + " WHERE id > ?")) {
            ps.setLong(1, rows);
            ps.executeUpdate();
        }
    }

    static void drop(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + NAME);
        }
    }
}
