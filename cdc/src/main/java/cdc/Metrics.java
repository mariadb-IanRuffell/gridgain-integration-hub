package cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * Publishes the reader's throughput into two small GridGain tables so the API's dashboard can read them:
 *  - cdc_status: one row of cumulative counters + current position/lag, refreshed after batches and as a heartbeat
 *  - cdc_table_counts: Sybase row count per table, refreshed every 3 seconds
 * Counters are cumulative (and restored on start); the dashboard derives rates from successive samples.
 */
final class Metrics {
    private static final long FLUSH_BUSY_MS = 1_000, FLUSH_IDLE_MS = 2_000, COUNTS_MS = 3_000;

    private final Cdc cdc;
    private long events, upserts, deletes, batches;
    private int lastBatchEvents;
    private long lastBatchMs, lastLatencyMs;
    private final Timestamp startedAt = new Timestamp(System.currentTimeMillis());
    private boolean dirty;
    private long lastFlush, lastCounts;

    Metrics(Cdc cdc) {
        this.cdc = cdc;
    }

    void ensureTables() throws SQLException {
        try (Statement st = cdc.gg().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS cdc_status (consumer VARCHAR(30) PRIMARY KEY, last_change_id BIGINT, "
                + "pending_events BIGINT, total_events BIGINT, total_upserts BIGINT, total_deletes BIGINT, batches BIGINT, "
                + "last_batch_events INT, last_batch_ms BIGINT, last_latency_ms BIGINT, started_at TIMESTAMP, updated_at TIMESTAMP) "
                + "WITH \"template=partitioned,backups=1,cache_name=cdc_status\"");
            st.execute("CREATE TABLE IF NOT EXISTS cdc_table_counts (table_name VARCHAR(30) PRIMARY KEY, "
                + "sybase_rows BIGINT, updated_at TIMESTAMP) WITH \"template=partitioned,backups=1,cache_name=cdc_table_counts\"");
        }
        try (PreparedStatement ps = cdc.gg().prepareStatement(
                "SELECT total_events, total_upserts, total_deletes, batches FROM cdc_status WHERE consumer = ?")) {
            ps.setString(1, Cdc.CONSUMER);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {   // continue the cumulative counters across restarts
                    events = rs.getLong(1); upserts = rs.getLong(2); deletes = rs.getLong(3); batches = rs.getLong(4);
                }
            }
        }
    }

    void record(Cdc.Result r) {
        if (r.events() == 0) return;
        events += r.events();
        upserts += r.upserts();
        deletes += r.deletes();
        batches++;
        lastBatchEvents = r.events();
        lastBatchMs = r.durationMs();
        // age of the oldest change in the batch when it landed in GridGain (approximate: compares Sybase and local clocks)
        lastLatencyMs = r.oldestChangeMs() == 0 ? 0 : Math.max(0, System.currentTimeMillis() - r.oldestChangeMs());
        dirty = true;
    }

    /** Call once per loop iteration; writes to GridGain only when something changed or the heartbeat is due. */
    void tick() throws SQLException {
        long now = System.currentTimeMillis();
        if ((dirty && now - lastFlush >= FLUSH_BUSY_MS) || now - lastFlush >= FLUSH_IDLE_MS) flush(now);
        if (now - lastCounts >= COUNTS_MS) counts(now);
    }

    private void flush(long now) throws SQLException {
        long offset = cdc.getOffset();
        long pending;
        try (Statement s = cdc.ase().createStatement();
             ResultSet rs = s.executeQuery("select count(*) from cdc_change_log where change_id > " + offset)) {
            rs.next();
            pending = rs.getLong(1);
        }
        try (PreparedStatement ps = cdc.gg().prepareStatement(
                "MERGE INTO cdc_status (consumer, last_change_id, pending_events, total_events, total_upserts, total_deletes, "
                    + "batches, last_batch_events, last_batch_ms, last_latency_ms, started_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            ps.setString(++i, Cdc.CONSUMER);
            ps.setLong(++i, offset);
            ps.setLong(++i, pending);
            ps.setLong(++i, events);
            ps.setLong(++i, upserts);
            ps.setLong(++i, deletes);
            ps.setLong(++i, batches);
            ps.setInt(++i, lastBatchEvents);
            ps.setLong(++i, lastBatchMs);
            ps.setLong(++i, lastLatencyMs);
            ps.setTimestamp(++i, startedAt);
            ps.setTimestamp(++i, new Timestamp(now));
            ps.executeUpdate();
        }
        dirty = false;
        lastFlush = now;
    }

    private void counts(long now) throws SQLException {
        try (PreparedStatement ps = cdc.gg().prepareStatement(
                "MERGE INTO cdc_table_counts (table_name, sybase_rows, updated_at) VALUES (?, ?, ?)")) {
            for (TableSpec t : Tables.ALL) {
                long n;
                try (Statement s = cdc.ase().createStatement(); ResultSet rs = s.executeQuery("select count(*) from " + t.name)) {
                    rs.next();
                    n = rs.getLong(1);
                }
                ps.setString(1, t.name);
                ps.setLong(2, n);
                ps.setTimestamp(3, new Timestamp(now));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        lastCounts = now;
    }
}
