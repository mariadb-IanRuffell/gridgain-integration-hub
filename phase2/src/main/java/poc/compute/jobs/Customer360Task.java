package poc.compute.jobs;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.compute.ComputeJob;

/**
 * Replaces sp_customer_360. One job on the node owning the customer; every query is pruned to that
 * node because all customer-owned tables are colocated on customer_id. Returns the same six result
 * sets as the procedure, as lists of column->value maps.
 */
public class Customer360Task extends AffinityTask<Integer> {
    @Override
    int customerId(Integer cid) {
        return cid;
    }

    @Override
    ComputeJob job(Integer cid) {
        return new Job(cid);
    }

    static class Job extends CustomerJob {
        private final int cid;

        Job(int cid) {
            this.cid = cid;
        }

        @Override
        public Object execute() {
            Timestamp now = new Timestamp(System.currentTimeMillis());

            // 1: profile
            List<LinkedHashMap<String, Object>> profile = Sql.rows(ignite,
                "SELECT c.customer_id, c.first_name, c.last_name, c.email, c.phone, c.nationality, c.segment, "
                    + "c.loyalty_tier, c.loyalty_points, c.marketing_opt_in, c.status, c.created_at, a.city, a.country "
                    + "FROM customer c LEFT JOIN address a ON a.customer_id = c.customer_id AND a.is_primary = TRUE "
                    + "WHERE c.customer_id = ?", cid);
            if (profile.isEmpty()) return fail(-1, 20002, "Customer not found");

            // 2: order summary
            List<LinkedHashMap<String, Object>> summary = Sql.rows(ignite,
                "SELECT COUNT(*) AS order_count, COALESCE(SUM(total_amount), 0) AS lifetime_spend, "
                    + "COALESCE(AVG(total_amount), 0) AS avg_order_value, MAX(order_date) AS last_order_date, "
                    + "COALESCE(SUM(CASE WHEN order_date >= ? THEN total_amount ELSE 0 END), 0) AS spend_12m "
                    + "FROM orders WHERE customer_id = ? AND status = 'COMPLETED'",
                Loyalty.minusDays(now, 365), cid);

            // 3: five most recent orders
            List<LinkedHashMap<String, Object>> recent = Sql.rows(ignite,
                "SELECT order_id, order_date, status, channel, total_amount, currency FROM orders "
                    + "WHERE customer_id = ? ORDER BY order_date DESC, order_id DESC LIMIT 5", cid);

            // 4: top three categories by spend (product is replicated, so the join stays local)
            List<LinkedHashMap<String, Object>> categories = Sql.rows(ignite,
                "SELECT p.category, SUM(oi.line_total) AS spend, SUM(oi.qty) AS qty "
                    + "FROM orders o "
                    + "JOIN order_item oi ON oi.order_id = o.order_id AND oi.customer_id = o.customer_id "
                    + "JOIN product p ON p.product_id = oi.product_id "
                    + "WHERE o.customer_id = ? AND o.status = 'COMPLETED' "
                    + "GROUP BY p.category ORDER BY SUM(oi.line_total) DESC, p.category LIMIT 3", cid);

            // 5: service interactions
            List<LinkedHashMap<String, Object>> interactions = Sql.rows(ignite,
                "SELECT COUNT(*) AS total_interactions, "
                    + "COALESCE(SUM(CASE WHEN status = 'OPEN' THEN 1 ELSE 0 END), 0) AS open_cases, "
                    + "MAX(opened_at) AS last_contact, COALESCE(AVG(CAST(sentiment AS DOUBLE)), 0) AS avg_sentiment "
                    + "FROM interaction WHERE customer_id = ?", cid);

            // 6: loyalty position and distance to next tier
            BigDecimal spend12 = (BigDecimal) summary.get(0).get("spend_12m");
            BigDecimal nextThreshold = (BigDecimal) Sql.scalar(ignite,
                "SELECT MIN(min_spend) FROM loyalty_tier_rule WHERE min_spend > ?", spend12);
            Object nextTier = nextThreshold == null ? null
                : Sql.scalar(ignite, "SELECT tier FROM loyalty_tier_rule WHERE min_spend = ?", nextThreshold);
            LinkedHashMap<String, Object> loyalty = new LinkedHashMap<>();
            loyalty.put("loyalty_tier", profile.get(0).get("loyalty_tier"));
            loyalty.put("loyalty_points", profile.get(0).get("loyalty_points"));
            loyalty.put("spend_12m", spend12);
            loyalty.put("next_tier", nextTier);
            loyalty.put("spend_to_next_tier", nextThreshold == null ? null : nextThreshold.subtract(spend12));

            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("profile", new ArrayList<Object>(profile));
            out.put("order_summary", new ArrayList<Object>(summary));
            out.put("recent_orders", new ArrayList<Object>(recent));
            out.put("top_categories", new ArrayList<Object>(categories));
            out.put("interactions", new ArrayList<Object>(interactions));
            out.put("loyalty", Loyalty.list(loyalty));
            return out;
        }
    }
}
