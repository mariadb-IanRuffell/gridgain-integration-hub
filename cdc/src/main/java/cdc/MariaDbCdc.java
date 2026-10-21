package cdc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trigger-based CDC from MariaDB to GridGain 8.9.
 *
 * <p>MariaDB triggers append (table, op, pk) to cdc_change_log (ids are gapless and commit-ordered, see sql generate_cdc.py). For each batch of log rows this class
 * collapses them to the distinct changed keys, re-reads the current row from MariaDB and MERGEs it into
 * GridGain, or DELETEs it when the row no longer exists. Applying current state (not individual
 * operations) makes every batch idempotent, so the offset is committed only after GridGain has the
 * data and a crash simply replays the last batch (at-least-once delivery, effectively-once result).
 */
final class MariaDbCdc implements CdcSource {
    static final String CONSUMER = "mariadb";
    private static final int IN_CHUNK = 500;
    private static final int BATCH = 1000;

    private final String dbUrl, dbUser, dbPass, ggUrl;
    private Connection db, gg;

    MariaDbCdc(String dbUrl, String dbUser, String dbPass, String ggUrl) throws SQLException {
        this.dbUrl = dbUrl; this.dbUser = dbUser; this.dbPass = dbPass; this.ggUrl = ggUrl;
        connect();
    }

    Connection source() { return db; }

    @Override public Connection gg() { return gg; }

    @Override public String consumer() { return CONSUMER; }

    @Override public long position() throws SQLException { return getOffset(); }

    @Override public long pending() throws SQLException {
        return queryLong("select count(*) from cdc_change_log where change_id > " + getOffset());
    }

    @Override public List<String> tableNames() { return Tables.ALL.stream().map(t -> t.name).toList(); }

    @Override public long countRows(String table) throws SQLException { return queryLong(db, "select count(*) from " + table); }

    @Override
    public void connect() throws SQLException {
        close();
        db = DriverManager.getConnection(dbUrl, dbUser, dbPass);
        gg = DriverManager.getConnection(ggUrl);
    }

    @Override
    public void close() {
        for (Connection c : new Connection[] {db, gg}) {
            try { if (c != null) c.close(); } catch (SQLException ignored) { }
        }
    }

    // ------------------------------------------------------------------ init (snapshot)

    /** Recreate the GridGain tables, copy a full snapshot, and position the offset at the pre-copy watermark. */
    @Override
    public void init() throws Exception {
        try (Statement st = gg.createStatement()) {
            for (TableSpec t : Tables.ALL) st.execute("DROP TABLE IF EXISTS " + t.name);
            for (String ddl : readResource("/gridgain-schema-mariadb.sql").split(";")) {
                if (!stripComments(ddl).isBlank()) st.execute(ddl);
            }
        }
        // Watermark first: changes made while the copy runs are replayed afterwards (idempotent).
        long watermark = queryLong("select ifnull(max(change_id), 0) from cdc_change_log");
        System.out.println("snapshot watermark change_id=" + watermark);
        for (TableSpec t : Tables.ALL) {
            long n = copyAll(t);
            System.out.printf("  %-18s %6d rows%n", t.name, n);
        }
        setOffset(watermark);
        System.out.println("init complete; streaming resumes after change_id=" + watermark);
    }

    private long copyAll(TableSpec t) throws SQLException {
        long total = 0;
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(t.selectSql());
             PreparedStatement merge = gg.prepareStatement(t.mergeSql())) {
            int pending = 0;
            while (rs.next()) {
                bind(merge, rs, t.columns.size());
                merge.addBatch();
                total++;
                if (++pending == BATCH) { merge.executeBatch(); pending = 0; }
            }
            if (pending > 0) merge.executeBatch();
        }
        return total;
    }

    // ------------------------------------------------------------------ streaming

    /** Apply up to {@code limit} change-log rows. Returns what was done; events==0 means caught up. */
    @Override
    public Result applyOnce(int limit) throws SQLException {
        long started = System.currentTimeMillis();
        long offset = getOffset();
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        long last = offset;
        long oldest = Long.MAX_VALUE;
        int events = 0;
        try (PreparedStatement ps = db.prepareStatement(
                "select change_id, table_name, pk_value, change_ts from cdc_change_log "
                    + "where change_id > ? order by change_id limit " + limit)) {
            ps.setLong(1, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    last = rs.getLong(1);
                    java.sql.Timestamp ts = rs.getTimestamp(4);
                    if (ts != null) oldest = Math.min(oldest, ts.getTime());
                    keys.computeIfAbsent(rs.getString(2), k -> new LinkedHashSet<>()).add(rs.getString(3).trim());
                    events++;
                }
            }
        }
        if (events == 0) return new Result(0, 0, 0, offset, 0, 0);

        int upserts = 0, deletes = 0;
        for (Map.Entry<String, Set<String>> e : keys.entrySet()) {
            TableSpec t = Tables.BY_NAME.get(e.getKey());
            if (t == null) throw new IllegalStateException("No mapping for table " + e.getKey());
            List<String> pks = new ArrayList<>(e.getValue());
            Set<String> found = new LinkedHashSet<>();
            try (PreparedStatement merge = gg.prepareStatement(t.mergeSql())) {
                for (int i = 0; i < pks.size(); i += IN_CHUNK) {
                    List<String> chunk = pks.subList(i, Math.min(i + IN_CHUNK, pks.size()));
                    try (PreparedStatement q = db.prepareStatement(t.selectSql() + " where t." + t.pk + " in ("
                            + "?, ".repeat(chunk.size() - 1) + "?)")) {
                        for (int k = 0; k < chunk.size(); k++) setPk(q, k + 1, t, chunk.get(k));
                        try (ResultSet rs = q.executeQuery()) {
                            while (rs.next()) {
                                found.add(rs.getObject(t.pkIndex + 1).toString().trim());
                                bind(merge, rs, t.columns.size());
                                merge.addBatch();
                                upserts++;
                            }
                        }
                    }
                }
                merge.executeBatch();
            }
            try (PreparedStatement del = gg.prepareStatement(t.deleteSql())) {
                boolean any = false;
                for (String pk : pks) {
                    if (found.contains(pk)) continue;
                    setPk(del, 1, t, pk);
                    del.addBatch();
                    any = true;
                    deletes++;
                }
                if (any) del.executeBatch();
            }
        }
        setOffset(last);   // only after GridGain has the data
        return new Result(events, upserts, deletes, last, oldest == Long.MAX_VALUE ? 0 : oldest,
            System.currentTimeMillis() - started);
    }

    // ------------------------------------------------------------------ status / verify

    @Override
    public void status() throws SQLException {
        long offset = getOffset();
        long max = queryLong("select ifnull(max(change_id), 0) from cdc_change_log");
        long pending = queryLong("select count(*) from cdc_change_log where change_id > " + offset);
        System.out.printf("consumer=%s offset=%d newest_change_id=%d pending_events=%d%n", CONSUMER, offset, max, pending);
        System.out.printf("%-18s %8s %8s%n", "table", "mariadb", "gridgain");
        for (TableSpec t : Tables.ALL) {
            System.out.printf("%-18s %8d %8d%n", t.name,
                queryLong(db, "select count(*) from " + t.name),
                queryLong(gg, "select count(*) from " + t.name));
        }
    }

    /** Compare every row (all columns) on both sides. Returns true when identical. */
    @Override
    public boolean verify() throws SQLException {
        boolean ok = true;
        System.out.printf("%-18s %8s %8s %8s %8s %8s%n", "table", "mariadb", "gridgain", "missing", "extra", "differ");
        for (TableSpec t : Tables.ALL) {
            Map<String, String> src = hashes(db, t.selectSql(), t);
            Map<String, String> dst = hashes(gg, t.gridSelectSql(), t);
            int missing = 0, extra = 0, differ = 0;
            List<String> samples = new ArrayList<>();
            for (Map.Entry<String, String> e : src.entrySet()) {
                String d = dst.get(e.getKey());
                if (d == null) { missing++; if (samples.size() < 3) samples.add("missing " + e.getKey()); }
                else if (!d.equals(e.getValue())) {
                    differ++;
                    if (samples.size() < 3) samples.add("differ " + e.getKey() + "\n    src: " + e.getValue() + "\n    dst: " + d);
                }
            }
            for (String k : dst.keySet()) if (!src.containsKey(k)) { extra++; if (samples.size() < 3) samples.add("extra " + k); }
            System.out.printf("%-18s %8d %8d %8d %8d %8d%n", t.name, src.size(), dst.size(), missing, extra, differ);
            samples.forEach(s -> System.out.println("   " + s));
            ok &= (missing + extra + differ) == 0;
        }
        System.out.println(ok ? "VERIFY OK: MariaDB and GridGain are identical" : "VERIFY FAILED");
        return ok;
    }

    private Map<String, String> hashes(Connection c, String sql, TableSpec t) throws SQLException {
        Map<String, String> m = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = t.columns.size();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) sb.append(norm(rs.getObject(i))).append('|');
                m.put(norm(rs.getObject(t.pkIndex + 1)), sb.toString());
            }
        }
        return m;
    }

    static String norm(Object o) {
        if (o == null) return "~";
        if (o instanceof BigDecimal b) return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
        if (o instanceof Timestamp ts) return Long.toString(ts.getTime());
        if (o instanceof Date d) return d.toString();
        if (o instanceof String s) return s.stripTrailing();
        return o.toString();
    }

    // ------------------------------------------------------------------ helpers

    static void bind(PreparedStatement ps, ResultSet rs, int n) throws SQLException {
        for (int i = 1; i <= n; i++) ps.setObject(i, rs.getObject(i));
    }

    static void setPk(PreparedStatement ps, int idx, TableSpec t, String pk) throws SQLException {
        if (t.pkIsString) ps.setString(idx, pk); else ps.setInt(idx, Integer.parseInt(pk));
    }

    long getOffset() throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("select last_change_id from cdc_offset where consumer = ?")) {
            ps.setString(1, CONSUMER);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new IllegalStateException("No offset for consumer '" + CONSUMER + "': run `init` first");
    }

    private void setOffset(long id) throws SQLException {
        try (PreparedStatement up = db.prepareStatement(
                "update cdc_offset set last_change_id = ?, updated_at = now() where consumer = ?")) {
            up.setLong(1, id);
            up.setString(2, CONSUMER);
            if (up.executeUpdate() > 0) return;
        }
        try (PreparedStatement ins = db.prepareStatement(
                "insert into cdc_offset (consumer, last_change_id, updated_at) values (?, ?, now())")) {
            ins.setString(1, CONSUMER);
            ins.setLong(2, id);
            ins.executeUpdate();
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long queryLong(String sql) throws SQLException {
        return queryLong(db, sql);
    }

    static String readResource(String path) throws java.io.IOException {
        try (var in = MariaDbCdc.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static String stripComments(String sql) {
        return java.util.Arrays.stream(sql.split("\n")).filter(l -> !l.trim().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n"));
    }
}
