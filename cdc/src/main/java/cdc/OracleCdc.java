package cdc;

import java.math.BigDecimal;
import java.sql.Connection;
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
 * Trigger-based CDC from Oracle to GridGain 8.9.
 *
 * <p>Row triggers append (table, op, pk) to cdc_change_log, created with ROWDEPENDENCIES so each row carries its own
 * COMMIT SCN (ORA_ROWSCN). Oracle readers never block on uncommitted rows, so reading "change_id > last" could skip a
 * change whose transaction commits late. Instead each poll takes a snapshot SCN and reads exactly the rows committed in
 * (last_scn, snapshot]; every commit at or below the snapshot is already visible, and later commits land in a later window.
 * Changed keys are collapsed, the current row is re-read from Oracle and MERGEd into GridGain (or DELETEd if gone), so
 * applying a window is idempotent and the offset advances only after GridGain has the data.
 */
final class OracleCdc implements CdcSource {
    static final String CONSUMER = "oracle";
    private static final int IN_CHUNK = 400, BATCH = 1000;
    private static final long IDLE_ADVANCE_MS = 5_000, PURGE_MS = 300_000;

    /** One replicated table. Column specs: "name:T" (read t.name) or "name=expr:T"; T = I int, D decimal, T timestamp, S string. */
    static final class Tbl {
        final String name, from;
        final String[] pk;               // primary-key column names (1 or 2)
        final char[] pkTypes;
        final List<String> cols = new ArrayList<>(), exprs = new ArrayList<>();
        final List<Character> types = new ArrayList<>();

        Tbl(String name, String from, String[] pk, char[] pkTypes, String... colSpecs) {
            this.name = name; this.from = from; this.pk = pk; this.pkTypes = pkTypes;
            for (String c : colSpecs) {
                char type = c.charAt(c.length() - 1);
                String body = c.substring(0, c.length() - 2);
                int eq = body.indexOf('=');
                cols.add(eq < 0 ? body : body.substring(0, eq));
                exprs.add(eq < 0 ? "t." + body : body.substring(eq + 1));
                types.add(type);
            }
        }

        String selectSql() { return "select " + String.join(", ", exprs) + " from " + from; }
        String mergeSql() { return "MERGE INTO " + name + " (" + String.join(", ", cols) + ") VALUES (" + "?, ".repeat(cols.size() - 1) + "?)"; }
        String deleteSql() {
            StringBuilder b = new StringBuilder("DELETE FROM " + name + " WHERE ");
            for (int i = 0; i < pk.length; i++) b.append(i > 0 ? " AND " : "").append(pk[i]).append(" = ?");
            return b.toString();
        }
        String gridSelectSql() { return "SELECT " + String.join(", ", cols) + " FROM " + name; }
        int pkIndex() { return cols.indexOf(pk[0]); }
    }

    static final List<Tbl> ALL = List.of(
        new Tbl("airport", "airport t", new String[] {"iata"}, new char[] {'S'}, "iata:S", "name:S", "city:S", "country:S", "tz:S"),
        new Tbl("flight", "flight t", new String[] {"flight_id"}, new char[] {'I'}, "flight_id:I", "flight_no:S", "origin:S", "destination:S",
            "dep_time:T", "arr_time:T", "distance_km:I", "aircraft:S", "status:S", "delay_minutes:I"),
        new Tbl("flight_inventory", "flight_inventory t", new String[] {"flight_id", "cabin"}, new char[] {'I', 'S'}, "flight_id:I", "cabin:S",
            "seats_total:I", "seats_available:I", "next_seat:I", "base_fare:D"),
        new Tbl("booking", "booking t", new String[] {"booking_id"}, new char[] {'I'}, "booking_id:I", "customer_id:I", "booking_ref:S", "status:S",
            "booked_at:T", "channel:S", "total_fare:D", "refunded:D", "currency:S"),
        new Tbl("booking_segment", "booking_segment t join booking b on b.booking_id = t.booking_id", new String[] {"segment_id"}, new char[] {'I'},
            "segment_id:I", "customer_id=b.customer_id:I", "booking_id:I", "flight_id:I", "cabin:S", "seat_no:S", "passenger_name:S",
            "fare:D", "status:S", "miles_earned:I"),
        new Tbl("flight_event", "flight_event t", new String[] {"event_id"}, new char[] {'I'}, "event_id:I", "flight_id:I", "event_type:S", "detail:S", "event_time:T"),
        new Tbl("compensation", "compensation t join booking_segment s on s.segment_id = t.segment_id join booking b on b.booking_id = s.booking_id",
            new String[] {"segment_id"}, new char[] {'I'}, "segment_id:I", "customer_id=b.customer_id:I", "amount:D", "reason:S", "created_at:T"));
    static final Map<String, Tbl> BY_NAME = new LinkedHashMap<>();
    static { ALL.forEach(t -> BY_NAME.put(t.name, t)); }

    private final String url, user, pass, ggUrl;
    private Connection ora, gg;
    private long lastIdleAdvance, lastPurge = System.currentTimeMillis();

    OracleCdc(String url, String user, String pass, String ggUrl) throws SQLException {
        this.url = url; this.user = user; this.pass = pass; this.ggUrl = ggUrl;
        connect();
    }

    @Override public void connect() throws SQLException {
        close();
        ora = DriverManager.getConnection(url, user, pass);
        gg = DriverManager.getConnection(ggUrl);
    }

    @Override public void close() {
        for (Connection c : new Connection[] {ora, gg}) { try { if (c != null) c.close(); } catch (SQLException ignored) { } }
    }

    @Override public Connection gg() { return gg; }
    @Override public String consumer() { return CONSUMER; }
    @Override public List<String> tableNames() { return ALL.stream().map(t -> t.name).toList(); }
    @Override public long countRows(String table) throws SQLException { return queryLong(ora, "select count(*) from " + table); }
    @Override public long position() throws SQLException { return getOffset(); }
    @Override public long pending() throws SQLException {
        return queryLong(ora, "select count(*) from cdc_change_log where ora_rowscn > " + getOffset());
    }

    // ------------------------------------------------------------------ init (snapshot)

    @Override public void init() throws Exception {
        try (Statement st = gg.createStatement()) {
            for (Tbl t : ALL) st.execute("DROP TABLE IF EXISTS " + t.name);
            for (String ddl : readResource("/gridgain-schema-oracle.sql").split(";")) {
                if (!stripComments(ddl).isBlank()) st.execute(ddl);
            }
        }
        long snap = currentScn();   // watermark first: changes made during the copy are replayed afterwards (idempotent)
        System.out.println("snapshot SCN=" + snap);
        for (Tbl t : ALL) System.out.printf("  %-18s %6d rows%n", t.name, copyAll(t));
        setOffset(snap);
        System.out.println("init complete; streaming resumes after SCN " + snap);
    }

    private long copyAll(Tbl t) throws SQLException {
        long total = 0;
        try (Statement s = ora.createStatement(); ResultSet rs = s.executeQuery(t.selectSql());
             PreparedStatement merge = gg.prepareStatement(t.mergeSql())) {
            int pending = 0;
            while (rs.next()) {
                bind(merge, rs, t);
                merge.addBatch();
                total++;
                if (++pending == BATCH) { merge.executeBatch(); pending = 0; }
            }
            if (pending > 0) merge.executeBatch();
        }
        return total;
    }

    // ------------------------------------------------------------------ streaming

    @Override public Result applyOnce(int limit) throws SQLException {
        long started = System.currentTimeMillis();
        long last = getOffset(), snap = currentScn();
        if (snap <= last) return new Result(0, 0, 0, last, 0, 0);

        Map<String, Set<String>> keys = new LinkedHashMap<>();
        long oldest = Long.MAX_VALUE;
        int events = 0;
        try (PreparedStatement ps = ora.prepareStatement(
                "select table_name, pk_value, change_ts from cdc_change_log where ora_rowscn > ? and ora_rowscn <= ? order by ora_rowscn, change_id")) {
            ps.setLong(1, last);
            ps.setLong(2, snap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp(3);
                    if (ts != null) oldest = Math.min(oldest, ts.getTime());
                    keys.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
                    events++;
                }
            }
        }
        long now = System.currentTimeMillis();
        if (events == 0) {   // nothing committed in the window: advance occasionally so the window stays small
            if (now - lastIdleAdvance > IDLE_ADVANCE_MS) { setOffset(snap); lastIdleAdvance = now; }
            maintenance(now, snap);
            return new Result(0, 0, 0, snap, 0, 0);
        }

        int upserts = 0, deletes = 0;
        for (Map.Entry<String, Set<String>> e : keys.entrySet()) {
            Tbl t = BY_NAME.get(e.getKey());
            if (t == null) throw new IllegalStateException("No mapping for table " + e.getKey());
            List<String> pks = new ArrayList<>(e.getValue());
            Set<String> found = new LinkedHashSet<>();
            try (PreparedStatement merge = gg.prepareStatement(t.mergeSql())) {
                for (int i = 0; i < pks.size(); i += IN_CHUNK) {
                    List<String> chunk = pks.subList(i, Math.min(i + IN_CHUNK, pks.size()));
                    try (PreparedStatement q = ora.prepareStatement(t.selectSql() + " where " + pkPredicate(t, chunk.size()))) {
                        int p = 0;
                        for (String pk : chunk) {
                            String[] parts = pk.split("\\|", -1);
                            for (int k = 0; k < t.pk.length; k++) setTyped(q, ++p, t.pkTypes[k], parts[k]);
                        }
                        try (ResultSet rs = q.executeQuery()) {
                            while (rs.next()) {
                                found.add(keyOf(rs, t));
                                bind(merge, rs, t);
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
                    String[] parts = pk.split("\\|", -1);
                    for (int k = 0; k < t.pk.length; k++) setTyped(del, k + 1, t.pkTypes[k], parts[k]);
                    del.addBatch();
                    any = true;
                    deletes++;
                }
                if (any) del.executeBatch();
            }
        }
        setOffset(snap);   // only after GridGain has the data
        maintenance(System.currentTimeMillis(), snap);
        return new Result(events, upserts, deletes, snap, oldest == Long.MAX_VALUE ? 0 : oldest, System.currentTimeMillis() - started);
    }

    /** Remove log rows that are already applied and older than 10 minutes (ORA_ROWSCN predicates scan the log, so keep it small). */
    private void maintenance(long now, long snap) throws SQLException {
        if (now - lastPurge < PURGE_MS) return;
        lastPurge = now;
        try (PreparedStatement ps = ora.prepareStatement(
                "delete from cdc_change_log where ora_rowscn <= ? and change_ts < sys_extract_utc(systimestamp) - interval '10' minute")) {
            ps.setLong(1, getOffset());
            ps.executeUpdate();
        }
    }

    private static String pkPredicate(Tbl t, int n) {
        if (t.pk.length == 1) return "t." + t.pk[0] + " in (" + "?, ".repeat(n - 1) + "?)";
        return "(t." + t.pk[0] + ", t." + t.pk[1] + ") in (" + "(?, ?), ".repeat(n - 1) + "(?, ?))";
    }

    private static String keyOf(ResultSet rs, Tbl t) throws SQLException {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < t.pk.length; k++) {
            int idx = t.cols.indexOf(t.pk[k]) + 1;
            b.append(k > 0 ? "|" : "").append(t.pkTypes[k] == 'I' ? Long.toString(rs.getLong(idx)) : rs.getString(idx).stripTrailing());
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ status / verify

    @Override public void status() throws SQLException {
        long off = getOffset();
        System.out.printf("consumer=%s offset_scn=%d current_scn=%d pending_events=%d%n", CONSUMER, off, currentScn(), pending());
        System.out.printf("%-18s %8s %8s%n", "table", "oracle", "gridgain");
        for (Tbl t : ALL) System.out.printf("%-18s %8d %8d%n", t.name, countRows(t.name), queryLong(gg, "select count(*) from " + t.name));
    }

    @Override public boolean verify() throws SQLException {
        boolean ok = true;
        System.out.printf("%-18s %8s %8s %8s %8s %8s%n", "table", "oracle", "gridgain", "missing", "extra", "differ");
        for (Tbl t : ALL) {
            Map<String, String> src = hashes(ora, t.selectSql(), t), dst = hashes(gg, t.gridSelectSql(), t);
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
        System.out.println(ok ? "VERIFY OK: Oracle and GridGain are identical" : "VERIFY FAILED");
        return ok;
    }

    private Map<String, String> hashes(Connection c, String sql, Tbl t) throws SQLException {
        Map<String, String> m = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = t.cols.size();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder(), key = new StringBuilder();
                for (int i = 1; i <= n; i++) sb.append(norm(value(rs, i, t, c == ora))).append('|');
                for (int k = 0; k < t.pk.length; k++) key.append(k > 0 ? "|" : "").append(norm(value(rs, t.cols.indexOf(t.pk[k]) + 1, t, c == ora)));
                m.put(key.toString(), sb.toString());
            }
        }
        return m;
    }

    /** Oracle's getObject returns oracle.sql.TIMESTAMP for TIMESTAMP columns; read those through getTimestamp. */
    private static Object value(ResultSet rs, int i, Tbl t, boolean oracleSide) throws SQLException {
        return oracleSide && t.types.get(i - 1) == 'T' ? rs.getTimestamp(i) : rs.getObject(i);
    }

    private static String norm(Object o) {
        if (o == null) return "~";
        if (o instanceof BigDecimal b) return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
        if (o instanceof Timestamp ts) return Long.toString(ts.getTime());
        if (o instanceof java.sql.Date d) return d.toString();
        if (o instanceof String s) return s.stripTrailing();
        return o.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static void bind(PreparedStatement ps, ResultSet rs, Tbl t) throws SQLException {
        for (int i = 1; i <= t.cols.size(); i++) {
            switch (t.types.get(i - 1)) {
                case 'I' -> { int v = rs.getInt(i); ps.setObject(i, rs.wasNull() ? null : (Object) v); }
                case 'D' -> ps.setObject(i, rs.getBigDecimal(i));
                case 'T' -> ps.setObject(i, rs.getTimestamp(i));
                default -> { String s = rs.getString(i); ps.setObject(i, s == null ? null : s.stripTrailing()); }
            }
        }
    }

    private static void setTyped(PreparedStatement ps, int idx, char type, String v) throws SQLException {
        if (type == 'I') ps.setLong(idx, Long.parseLong(v)); else ps.setString(idx, v);
    }

    private long currentScn() throws SQLException {
        return queryLong(ora, "select dbms_flashback.get_system_change_number from dual");
    }

    long getOffset() throws SQLException {
        try (PreparedStatement ps = ora.prepareStatement("select last_scn from cdc_offset where consumer = ?")) {
            ps.setString(1, CONSUMER);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        }
        throw new IllegalStateException("No offset for consumer '" + CONSUMER + "': run `init` first");
    }

    private void setOffset(long scn) throws SQLException {
        try (PreparedStatement ps = ora.prepareStatement(
                "merge into cdc_offset o using (select ? as consumer from dual) d on (o.consumer = d.consumer) "
                    + "when matched then update set last_scn = ?, updated_at = sys_extract_utc(systimestamp) "
                    + "when not matched then insert (consumer, last_scn, updated_at) values (d.consumer, ?, sys_extract_utc(systimestamp))")) {
            ps.setString(1, CONSUMER);
            ps.setLong(2, scn);
            ps.setLong(3, scn);
            ps.executeUpdate();
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static String readResource(String path) throws java.io.IOException {
        try (var in = OracleCdc.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String stripComments(String sql) {
        return java.util.Arrays.stream(sql.split("\n")).filter(l -> !l.trim().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n"));
    }
}
