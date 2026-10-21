package poc.compute.jobs;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import poc.compute.jobs.OfferModel.Offer;
import poc.compute.jobs.OfferModel.Product;
import poc.compute.jobs.OfferModel.Profile;
import poc.compute.jobs.OfferModel.Purchase;
import poc.compute.jobs.OfferModel.Trip;

/**
 * Generates personalised offers from each customer's buying behaviour (see OfferModel for the scoring). One job runs on EACH node and handles
 * only the customers that node owns: purchase history, churn band and the next flight are read with LOCAL queries (all customer-owned tables,
 * MariaDB and Oracle, are colocated on customer_id). Only the "customers like you" signal needs the whole cluster, and it is one small
 * distributed aggregate (buyers per tier and product). Offers are written to customer_offer, replacing the customer's previous ones, so a
 * re-run is idempotent. Nothing leaves the cluster.
 *
 * Argument map (all optional): top_n (1..10, default 3), customer_id (just this customer), as_of (epoch millis, default now).
 */
public class RecommendOffersTask extends ComputeTaskAdapter<Map<String, Object>, Map<String, Object>> {
    static final String DDL = "CREATE TABLE IF NOT EXISTS customer_offer (customer_id INT, product_id INT, rank_no INT, sku VARCHAR(20), "
        + "product_name VARCHAR(100), category VARCHAR(12), score DOUBLE, discount_pct INT, list_price DECIMAL(10,2), offer_price DECIMAL(10,2), "
        + "reason VARCHAR(300), generated_at TIMESTAMP, expires_at TIMESTAMP, PRIMARY KEY (customer_id, product_id)) "
        + "WITH \"template=partitioned,backups=1,affinity_key=customer_id,cache_name=customer_offer,atomicity=transactional\"";

    @IgniteInstanceResource
    private transient Ignite ignite;

    private transient Integer singleCustomer;
    private transient long asOf;

    @Override
    public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, Map<String, Object> arg) {
        int topN = arg == null || arg.get("top_n") == null ? 3 : Math.max(1, Math.min(10, ((Number) arg.get("top_n")).intValue()));
        asOf = arg == null || arg.get("as_of") == null ? System.currentTimeMillis() : ((Number) arg.get("as_of")).longValue();
        singleCustomer = arg == null || arg.get("customer_id") == null ? null : ((Number) arg.get("customer_id")).intValue();
        Sql.exec(ignite, DDL);   // once per run; the table is created on first use

        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();
        if (singleCustomer != null) {
            jobs.put(new Job(topN, singleCustomer, asOf), ignite.affinity("customer").mapKeyToNode(singleCustomer));
        } else {
            for (ClusterNode n : subgrid) jobs.put(new Job(topN, null, asOf), n);
        }
        return jobs;
    }

    @Override
    public Map<String, Object> reduce(List<ComputeJobResult> results) {
        long customers = 0, withOffers = 0, offers = 0, discountSum = 0;
        double scoreSum = 0;
        Map<String, Long> byCategory = new TreeMap<>();
        for (ComputeJobResult r : results) {
            if (r.getException() != null) throw r.getException();
            Map<String, Object> part = r.getData();
            customers += (Long) part.get("customers");
            withOffers += (Long) part.get("customers_with_offers");
            offers += (Long) part.get("offers");
            discountSum += (Long) part.get("discount_sum");
            scoreSum += (Double) part.get("score_sum");
            @SuppressWarnings("unchecked") Map<String, Long> cats = (Map<String, Long>) part.get("by_category");
            cats.forEach((k, v) -> byCategory.merge(k, v, Long::sum));
        }
        if (singleCustomer != null && customers == 0) return AffinityTask.fail(-1, 20002, "Customer not found or not active");
        if (singleCustomer == null) purgeStale(asOf);

        List<Object> cats = new ArrayList<>();
        byCategory.forEach((k, v) -> {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("category", k);
            row.put("offers", v);
            cats.add(row);
        });
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("rc", 0);
        out.put("customers", customers);
        out.put("customers_with_offers", withOffers);
        out.put("offers", offers);
        out.put("avg_discount_pct", offers == 0 ? 0.0 : Math.round(discountSum * 100.0 / offers) / 100.0);
        out.put("avg_score", offers == 0 ? 0.0 : Math.round(scoreSum * 10_000.0 / offers) / 10_000.0);
        out.put("by_category", cats);
        return out;
    }

    /** After a full run, drop offers that were not regenerated: customers that were deleted or became inactive since the last run. */
    void purgeStale(long runStartMs) {
        Sql.exec(ignite, "DELETE FROM customer_offer WHERE generated_at < ?", new Timestamp(runStartMs));
    }

    static class Job extends ComputeJobAdapter {
        @IgniteInstanceResource
        private transient Ignite ignite;

        private final int topN;
        private final Integer onlyCustomer;
        private final long asOfMillis;

        Job(int topN, Integer onlyCustomer, long asOfMillis) {
            this.topN = topN;
            this.onlyCustomer = onlyCustomer;
            this.asOfMillis = asOfMillis;
        }

        @Override
        public Object execute() {
            Timestamp asOf = new Timestamp(asOfMillis);
            ClusterNode me = ignite.cluster().localNode();

            List<Product> catalogue = new ArrayList<>();
            for (Map<String, Object> r : Sql.rows(ignite, true, "SELECT product_id, sku, name, category, unit_price, stock_qty, active FROM product ORDER BY product_id"))
                catalogue.add(new Product(((Number) r.get("product_id")).intValue(), (String) r.get("sku"), (String) r.get("name"), (String) r.get("category"),
                    (BigDecimal) r.get("unit_price"), ((Number) r.get("stock_qty")).intValue(), (Boolean) r.get("active")));
            Map<Integer, String> categoryOf = new HashMap<>();
            catalogue.forEach(p -> categoryOf.put(p.id, p.category));

            // "customers like you": share of each tier's active customers who bought each product (cluster-wide, tiny result)
            Map<String, Long> tierSize = new HashMap<>();
            for (Map<String, Object> r : Sql.rows(ignite, false, "SELECT loyalty_tier AS tier, COUNT(*) AS n FROM customer WHERE status = 'ACTIVE' GROUP BY loyalty_tier"))
                tierSize.put((String) r.get("tier"), ((Number) r.get("n")).longValue());
            Map<String, Map<Integer, Double>> peers = new HashMap<>();
            for (Map<String, Object> r : Sql.rows(ignite, false,
                "SELECT c.loyalty_tier AS tier, oi.product_id AS product_id, COUNT(DISTINCT o.customer_id) AS buyers FROM order_item oi "
                    + "JOIN orders o ON o.order_id = oi.order_id AND o.customer_id = oi.customer_id "
                    + "JOIN customer c ON c.customer_id = o.customer_id WHERE o.status = 'COMPLETED' AND c.status = 'ACTIVE' "
                    + "GROUP BY c.loyalty_tier, oi.product_id")) {
                String tier = (String) r.get("tier");
                long size = tierSize.getOrDefault(tier, 0L);
                if (size > 0) peers.computeIfAbsent(tier, k -> new HashMap<>()).put(((Number) r.get("product_id")).intValue(),
                    ((Number) r.get("buyers")).doubleValue() / size);
            }

            // this node's customers: purchase history (24 months), churn band, next confirmed flight; all LOCAL
            Map<Integer, List<Purchase>> history = new HashMap<>();
            for (Map<String, Object> r : Sql.rows(ignite, true,
                "SELECT o.customer_id AS customer_id, oi.product_id AS product_id, SUM(oi.qty) AS qty, SUM(oi.line_total) AS spend, MAX(o.order_date) AS last_order "
                    + "FROM order_item oi JOIN orders o ON o.order_id = oi.order_id AND o.customer_id = oi.customer_id "
                    + "WHERE o.status = 'COMPLETED' AND o.order_date >= ? GROUP BY o.customer_id, oi.product_id", Loyalty.minusDays(asOf, 730))) {
                int pid = ((Number) r.get("product_id")).intValue();
                history.computeIfAbsent(((Number) r.get("customer_id")).intValue(), k -> new ArrayList<>()).add(new Purchase(pid, categoryOf.getOrDefault(pid, "?"),
                    ((Number) r.get("qty")).longValue(), (BigDecimal) r.get("spend"), ((Timestamp) r.get("last_order")).getTime()));
            }
            Map<Integer, String> risk = new HashMap<>();
            for (Map<String, Object> r : Sql.rows(ignite, true, "SELECT customer_id, risk_band FROM customer_score"))
                risk.put(((Number) r.get("customer_id")).intValue(), (String) r.get("risk_band"));
            Map<Integer, Trip> trips = new HashMap<>();
            for (Map<String, Object> r : Sql.rows(ignite, true,
                "SELECT b.customer_id AS customer_id, f.flight_no AS flight_no, f.origin AS origin, f.destination AS destination, f.dep_time AS dep_time, "
                    + "f.status AS fstatus, f.delay_minutes AS delay FROM booking_segment s "
                    + "JOIN booking b ON b.booking_id = s.booking_id AND b.customer_id = s.customer_id JOIN flight f ON f.flight_id = s.flight_id "
                    + "WHERE b.status = 'CONFIRMED' AND s.status IN " + Travel.ACTIVE_SEGMENTS + " AND f.dep_time >= ? ORDER BY f.dep_time", asOf)) {
                int cid = ((Number) r.get("customer_id")).intValue();
                if (trips.containsKey(cid)) continue;   // ordered by departure: the first row is the next flight
                boolean disrupted = "CANCELLED".equals(r.get("fstatus")) || ((Number) r.get("delay")).intValue() >= 180;
                trips.put(cid, new Trip((String) r.get("flight_no"), (String) r.get("origin"), (String) r.get("destination"),
                    ((Timestamp) r.get("dep_time")).getTime(), disrupted));
            }

            long customers = 0, withOffers = 0, offers = 0, discountSum = 0;
            double scoreSum = 0;
            Map<String, Long> byCategory = new TreeMap<>();
            Timestamp generated = new Timestamp(asOfMillis);
            for (Map<String, Object> c : Sql.rows(ignite, true, "SELECT customer_id, loyalty_tier FROM customer WHERE status = 'ACTIVE' ORDER BY customer_id")) {
                int cid = ((Number) c.get("customer_id")).intValue();
                if (onlyCustomer != null && onlyCustomer != cid) continue;
                if (!ignite.affinity("customer").isPrimary(me, cid)) continue;   // the owner generates, not a backup
                customers++;

                Profile profile = new Profile(cid, (String) c.get("loyalty_tier"), risk.get(cid), history.getOrDefault(cid, Collections.emptyList()), trips.get(cid));
                List<Offer> made = OfferModel.recommend(profile, catalogue, peers, asOfMillis, topN);

                Sql.exec(ignite, "DELETE FROM customer_offer WHERE customer_id = ?", cid);
                for (Offer o : made) {
                    Sql.exec(ignite, "MERGE INTO customer_offer (customer_id, product_id, rank_no, sku, product_name, category, score, discount_pct, list_price, "
                        + "offer_price, reason, generated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        cid, o.productId, o.rank, o.sku, o.productName, o.category, o.score, o.discountPct, o.listPrice, o.offerPrice, o.reason,
                        generated, new Timestamp(o.expiresMs));
                    offers++;
                    discountSum += o.discountPct;
                    scoreSum += o.score;
                    byCategory.merge(o.category, 1L, Long::sum);
                }
                if (!made.isEmpty()) withOffers++;
            }
            ignite.log().info("RecommendOffersTask: " + offers + " offers for " + customers + " customers owned by this node");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("customers", customers);
            out.put("customers_with_offers", withOffers);
            out.put("offers", offers);
            out.put("discount_sum", discountSum);
            out.put("score_sum", scoreSum);
            out.put("by_category", byCategory);
            return out;
        }
    }
}
