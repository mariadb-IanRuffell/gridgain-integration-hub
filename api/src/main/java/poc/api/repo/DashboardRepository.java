package poc.api.repo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the table sizes and the CDC reader's published stats (cdc_status, cdc_table_counts) from GridGain. */
@Repository
public class DashboardRepository {
    public static final List<String> TABLES = List.of("customer", "address", "product", "orders", "order_item",
        "payment", "interaction", "loyalty_txn", "customer_score", "loyalty_tier_rule");

    public record CdcRow(long lastChangeId, long pendingEvents, long totalEvents, long totalUpserts, long totalDeletes,
                         long batches, int lastBatchEvents, long lastBatchMs, long lastLatencyMs, Instant startedAt,
                         Instant updatedAt) {}

    public record SybaseCount(long rows, Instant countedAt) {}

    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Long> gridGainRows() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String t : TABLES) out.put(t, jdbc.sql("SELECT COUNT(*) FROM " + t).query(Long.class).single());
        return out;
    }

    public Map<String, SybaseCount> sybaseRows() {
        Map<String, SybaseCount> out = new LinkedHashMap<>();
        jdbc.sql("SELECT table_name, sybase_rows, updated_at FROM cdc_table_counts").query((rs, n) -> {
            Cols c = new Cols(rs);
            out.put(c.string(), new SybaseCount(rs.getLong(2), rs.getTimestamp(3).toInstant()));
            return null;
        }).list();
        return out;
    }

    public Optional<CdcRow> cdcStatus() {
        return jdbc.sql("SELECT last_change_id, pending_events, total_events, total_upserts, total_deletes, batches, "
                + "last_batch_events, last_batch_ms, last_latency_ms, started_at, updated_at FROM cdc_status WHERE consumer = 'gridgain'")
            .query((rs, n) -> new CdcRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getInt(7), rs.getLong(8), rs.getLong(9), rs.getTimestamp(10).toInstant(),
                rs.getTimestamp(11).toInstant())).optional();
    }

    public List<String> serverNodes() {
        return jdbc.sql("SELECT consistent_id FROM sys.nodes WHERE is_client = FALSE ORDER BY consistent_id")
            .query(String.class).list();
    }
}
