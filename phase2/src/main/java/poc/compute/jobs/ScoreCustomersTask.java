package poc.compute.jobs;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
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
 * Replaces sp_score_customers. The stored procedure walks every customer with a cursor and runs three
 * queries per row. Here one job runs on EACH node and scores only the customers that node owns, using
 * three set-based LOCAL aggregate queries instead of per-row lookups (colocation guarantees the node has
 * all of a customer's orders and interactions). Results are MERGEd into customer_score, and per-node
 * band summaries are combined in reduce().
 *
 * Argument: as-of time in epoch millis (Long), or null for "now".
 */
public class ScoreCustomersTask extends ComputeTaskAdapter<Long, List<Object>> {
    @Override
    public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, Long asOfMillis) {
        long asOf = asOfMillis != null ? asOfMillis : System.currentTimeMillis();
        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();
        for (ClusterNode n : subgrid) jobs.put(new Job(asOf), n);
        return jobs;
    }

    @Override
    public List<Object> reduce(List<ComputeJobResult> results) {
        Map<String, long[]> bands = new TreeMap<>();   // band -> {customers, score sum}
        for (ComputeJobResult r : results) {
            if (r.getException() != null) throw r.getException();
            Map<String, List<Long>> part = r.getData();
            for (Map.Entry<String, List<Long>> e : part.entrySet()) {
                long[] acc = bands.computeIfAbsent(e.getKey(), k -> new long[2]);
                acc[0] += e.getValue().get(0);
                acc[1] += e.getValue().get(1);
            }
        }
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : bands.entrySet()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("risk_band", e.getKey());
            row.put("customers", e.getValue()[0]);
            row.put("avg_score", e.getValue()[1] / e.getValue()[0]);   // integer average, like ASE avg(int)
            out.add(row);
        }
        return out;
    }

    /** Churn score 0..100: recency (0-60) + 12-month order count (0-30) + open cases (0-10) + negative sentiment (10). */
    static int churnScore(long recencyDays, long orders12m, long openCases, double avgSentiment) {
        int score = (recencyDays <= 90 ? 0 : recencyDays <= 180 ? 20 : recencyDays <= 365 ? 40 : 60)
            + (orders12m >= 4 ? 0 : orders12m >= 2 ? 10 : orders12m == 1 ? 20 : 30)
            + (int) Math.min(openCases * 5, 10)
            + (avgSentiment < 0 ? 10 : 0);
        return Math.min(score, 100);
    }

    static String riskBand(int score) {
        return score < 30 ? "LOW" : score < 60 ? "MEDIUM" : "HIGH";
    }

    static class Job extends ComputeJobAdapter {
        @IgniteInstanceResource
        private transient Ignite ignite;

        private final long asOfMillis;

        Job(long asOfMillis) {
            this.asOfMillis = asOfMillis;
        }

        @Override
        public Object execute() {
            Timestamp asOf = new Timestamp(asOfMillis);
            Timestamp cutoff = Loyalty.minusDays(asOf, 365);
            ClusterNode me = ignite.cluster().localNode();

            // Local, set-based aggregates (one pass each) replace three per-customer queries.
            Map<Integer, Timestamp> lastOrder = new HashMap<>();
            Map<Integer, Long> orders12m = new HashMap<>();
            for (Map<String, Object> r : Sql.rows(ignite, true,
                "SELECT customer_id, MAX(order_date) AS last_order, "
                    + "SUM(CASE WHEN order_date >= ? THEN 1 ELSE 0 END) AS orders_12m "
                    + "FROM orders WHERE status = 'COMPLETED' AND order_date <= ? GROUP BY customer_id", cutoff, asOf)) {
                lastOrder.put((Integer) r.get("customer_id"), (Timestamp) r.get("last_order"));
                orders12m.put((Integer) r.get("customer_id"), ((Number) r.get("orders_12m")).longValue());
            }
            Map<Integer, Long> openCases = new HashMap<>();
            for (Map<String, Object> r : Sql.rows(ignite, true,
                "SELECT customer_id, COUNT(*) AS n FROM interaction WHERE status = 'OPEN' GROUP BY customer_id"))
                openCases.put((Integer) r.get("customer_id"), ((Number) r.get("n")).longValue());
            Map<Integer, Double> avgSentiment = new HashMap<>();
            for (Map<String, Object> r : Sql.rows(ignite, true,
                "SELECT customer_id, AVG(CAST(sentiment AS DOUBLE)) AS s FROM interaction "
                    + "WHERE opened_at >= ? GROUP BY customer_id", cutoff))
                avgSentiment.put((Integer) r.get("customer_id"), ((Number) r.get("s")).doubleValue());

            Map<String, List<Long>> summary = new TreeMap<>();
            for (Map<String, Object> c : Sql.rows(ignite, true,
                    "SELECT customer_id FROM customer WHERE status = 'ACTIVE'")) {
                int cid = (Integer) c.get("customer_id");
                if (!ignite.affinity("customer").isPrimary(me, cid)) continue;   // owner scores it, not a backup

                Timestamp last = lastOrder.get(cid);
                // ASE datediff(dd, a, b) counts calendar-day boundaries crossed, not elapsed 24h periods.
                long recency = last == null ? 9999
                    : ChronoUnit.DAYS.between(last.toLocalDateTime().toLocalDate(), asOf.toLocalDateTime().toLocalDate());
                long o12 = orders12m.getOrDefault(cid, 0L);
                long open = openCases.getOrDefault(cid, 0L);
                double sent = avgSentiment.getOrDefault(cid, 0.0);

                int score = churnScore(recency, o12, open, sent);
                String band = riskBand(score);

                Sql.exec(ignite, "MERGE INTO customer_score (customer_id, churn_score, risk_band, scored_at) "
                    + "VALUES (?, ?, ?, ?)", cid, score, band, asOf);

                List<Long> acc = summary.computeIfAbsent(band, k -> new ArrayList<>(java.util.Arrays.asList(0L, 0L)));
                acc.set(0, acc.get(0) + 1);
                acc.set(1, acc.get(1) + score);
            }
            long scored = 0;
            for (List<Long> v : summary.values()) scored += v.get(0);
            ignite.log().info("ScoreCustomersTask: scored " + scored + " customers owned by this node");
            return summary;
        }
    }
}
