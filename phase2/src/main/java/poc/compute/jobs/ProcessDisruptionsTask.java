package poc.compute.jobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;

/**
 * Replaces the Oracle procedure sp_process_disruptions. The procedure walks every affected segment with a cursor and MERGEs a
 * compensation row each time; here one job per node handles the segments that node owns, using one local query, and the per-reason
 * summaries are combined in reduce().
 *
 * Rule: cancelled flight 100% of the fare; delay >= 720 min 75%, >= 360 min 50%, otherwise 25%. Considered: confirmed bookings,
 * segments TICKETED/CHECKED_IN/FLOWN, flights CANCELLED or (DELAYED/ARRIVED/DEPARTED with delay >= min_delay).
 * Argument map: min_delay (Integer, default 180), as_of (epoch millis, default now).
 */
public class ProcessDisruptionsTask extends ComputeTaskAdapter<Map<String, Object>, List<Object>> {
    @Override
    public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, Map<String, Object> arg) {
        int minDelay = arg == null || arg.get("min_delay") == null ? 180 : ((Number) arg.get("min_delay")).intValue();
        long asOf = arg == null || arg.get("as_of") == null ? System.currentTimeMillis() : ((Number) arg.get("as_of")).longValue();
        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();
        for (ClusterNode n : subgrid) jobs.put(new Job(minDelay, asOf), n);
        return jobs;
    }

    @Override
    public List<Object> reduce(List<ComputeJobResult> results) {
        Map<String, Object[]> byReason = new TreeMap<>();   // reason -> {segments, total}
        for (ComputeJobResult r : results) {
            if (r.getException() != null) throw r.getException();
            Map<String, List<Object>> part = r.getData();
            for (Map.Entry<String, List<Object>> e : part.entrySet()) {
                Object[] acc = byReason.computeIfAbsent(e.getKey(), k -> new Object[] {0L, BigDecimal.ZERO});
                acc[0] = (Long) acc[0] + ((Number) e.getValue().get(0)).longValue();
                acc[1] = ((BigDecimal) acc[1]).add((BigDecimal) e.getValue().get(1));
            }
        }
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : byReason.entrySet()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("reason", e.getKey());
            row.put("segments", e.getValue()[0]);
            row.put("total_amount", e.getValue()[1]);
            out.add(row);
        }
        return out;
    }

    /** Compensation reason code: cancelled flight, delay of 720+ / 360+ minutes, otherwise a short delay. */
    static String reason(boolean cancelled, int delayMinutes) {
        return cancelled ? "CANCELLED" : delayMinutes >= 720 ? "DELAY_720" : delayMinutes >= 360 ? "DELAY_360" : "DELAY_SHORT";
    }

    /** Share of the fare compensated: 100% cancelled, 75% / 50% for long delays, 25% otherwise. */
    static BigDecimal share(boolean cancelled, int delayMinutes) {
        return cancelled ? BigDecimal.ONE : delayMinutes >= 720 ? new BigDecimal("0.75") : delayMinutes >= 360 ? new BigDecimal("0.5") : new BigDecimal("0.25");
    }

    static class Job extends ComputeJobAdapter {
        @IgniteInstanceResource
        private transient Ignite ignite;

        private final int minDelay;
        private final long asOfMillis;

        Job(int minDelay, long asOfMillis) {
            this.minDelay = minDelay;
            this.asOfMillis = asOfMillis;
        }

        @Override
        public Object execute() {
            Timestamp asOf = new Timestamp(asOfMillis);
            ClusterNode me = ignite.cluster().localNode();
            Map<String, List<Object>> summary = new TreeMap<>();
            for (Map<String, Object> s : Sql.rows(ignite, true,
                "SELECT s.segment_id, s.customer_id, s.fare, f.status AS fstatus, f.delay_minutes FROM booking_segment s "
                    + "JOIN booking b ON b.booking_id = s.booking_id AND b.customer_id = s.customer_id AND b.status = 'CONFIRMED' "
                    + "JOIN flight f ON f.flight_id = s.flight_id "
                    + "WHERE s.status IN ('TICKETED', 'CHECKED_IN', 'FLOWN') AND (f.status = 'CANCELLED' OR "
                    + "(f.status IN ('DELAYED', 'ARRIVED', 'DEPARTED') AND f.delay_minutes >= ?)) ORDER BY s.segment_id", minDelay)) {
                int cid = ((Number) s.get("customer_id")).intValue();
                if (!ignite.affinity("booking").isPrimary(me, cid)) continue;     // the owner processes it, not a backup

                int delay = ((Number) s.get("delay_minutes")).intValue();
                boolean cancelled = "CANCELLED".equals(s.get("fstatus"));
                String reason = reason(cancelled, delay);
                BigDecimal amount = ((BigDecimal) s.get("fare")).multiply(share(cancelled, delay)).setScale(2, RoundingMode.HALF_UP);

                Sql.exec(ignite, "MERGE INTO compensation (segment_id, customer_id, amount, reason, created_at) VALUES (?, ?, ?, ?, ?)",
                    s.get("segment_id"), cid, amount, reason, asOf);

                List<Object> acc = summary.computeIfAbsent(reason, k -> new ArrayList<>(java.util.Arrays.asList(0L, BigDecimal.ZERO)));
                acc.set(0, (Long) acc.get(0) + 1);
                acc.set(1, ((BigDecimal) acc.get(1)).add(amount));
            }
            ignite.log().info("ProcessDisruptionsTask: processed " + summary.values().stream().mapToLong(v -> (Long) v.get(0)).sum()
                + " segments owned by this node");
            return summary;
        }
    }
}
