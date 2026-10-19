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
    /** A replicated table and the source system it comes from. */
    public record TableRef(String name, String source) {}

    /** Source label of tables that exist only in GridGain (written by compute jobs): no source system to compare with. */
    public static final String NATIVE = "gridgain";

    public static final List<TableRef> TABLES = List.of(
        new TableRef("customer", "sybase"), new TableRef("address", "sybase"), new TableRef("product", "sybase"),
        new TableRef("orders", "sybase"), new TableRef("order_item", "sybase"), new TableRef("payment", "sybase"),
        new TableRef("interaction", "sybase"), new TableRef("loyalty_txn", "sybase"), new TableRef("customer_score", "sybase"),
        new TableRef("loyalty_tier_rule", "sybase"),
        new TableRef("airport", "oracle"), new TableRef("flight", "oracle"), new TableRef("flight_inventory", "oracle"),
        new TableRef("booking", "oracle"), new TableRef("booking_segment", "oracle"), new TableRef("flight_event", "oracle"),
        new TableRef("compensation", "oracle"),
        new TableRef("customer_offer", NATIVE));

    /** Consumer name each CDC reader publishes under in cdc_status. Sybase's is historical ("gridgain"). */
    public static final String SYBASE_CONSUMER = "gridgain", ORACLE_CONSUMER = "oracle";

    public record CdcRow(long lastChangeId, long pendingEvents, long totalEvents, long totalUpserts, long totalDeletes,
                         long batches, int lastBatchEvents, long lastBatchMs, long lastLatencyMs, Instant startedAt,
                         Instant updatedAt) {}

    public record SourceCount(long rows, Instant countedAt) {}

    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Long> gridGainRows() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (TableRef t : TABLES) {
            try {
                out.put(t.name(), jdbc.sql("SELECT COUNT(*) FROM " + t.name()).query(Long.class).single());
            } catch (org.springframework.dao.DataAccessException e) {
                if (!NATIVE.equals(t.source())) throw e;
                out.put(t.name(), 0L);      // a GridGain-only table that has not been created yet
            }
        }
        return out;
    }

    public Map<String, SourceCount> sourceRows() {
        Map<String, SourceCount> out = new LinkedHashMap<>();
        jdbc.sql("SELECT table_name, sybase_rows, updated_at FROM cdc_table_counts").query((rs, n) -> {
            Cols c = new Cols(rs);
            out.put(c.string(), new SourceCount(rs.getLong(2), rs.getTimestamp(3).toInstant()));
            return null;
        }).list();
        return out;
    }

    public Optional<CdcRow> cdcStatus(String consumer) {
        return jdbc.sql("SELECT last_change_id, pending_events, total_events, total_upserts, total_deletes, batches, "
                + "last_batch_events, last_batch_ms, last_latency_ms, started_at, updated_at FROM cdc_status WHERE consumer = ?").param(consumer)
            .query((rs, n) -> new CdcRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getInt(7), rs.getLong(8), rs.getLong(9), rs.getTimestamp(10).toInstant(),
                rs.getTimestamp(11).toInstant())).optional();
    }

    public List<String> serverNodes() {
        return jdbc.sql("SELECT consistent_id FROM sys.nodes WHERE is_client = FALSE ORDER BY consistent_id")
            .query(String.class).list();
    }
}
