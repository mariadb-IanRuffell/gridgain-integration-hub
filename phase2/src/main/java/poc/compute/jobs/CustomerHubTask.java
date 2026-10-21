package poc.compute.jobs;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.ignite.compute.ComputeJob;

/**
 * HUB JOB (no equivalent in either source system): one view of a customer that joins data from BOTH systems.
 *   MariaDB (CRM / commerce): customer profile, loyalty tier and points, completed orders.
 *   Oracle (travel):         bookings, miles, upcoming trips and the ones hit by a delay or cancellation.
 * Customer, orders, booking and booking_segment are all colocated on customer_id, so everything is read on the owning node.
 * lifetime_value adds retail spend (MariaDB) and travel spend (Oracle), which neither system could compute alone.
 */
public class CustomerHubTask extends AffinityTask<Integer> {
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
            List<LinkedHashMap<String, Object>> profile = Sql.rows(ignite,
                "SELECT customer_id, first_name, last_name, email, nationality, segment, loyalty_tier, loyalty_points, status "
                    + "FROM customer WHERE customer_id = ?", cid);
            if (profile.isEmpty()) return fail(-1, 20002, "Customer not found");

            LinkedHashMap<String, Object> retail = Sql.rows(ignite,
                "SELECT COUNT(*) AS orders, COALESCE(SUM(total_amount), 0) AS retail_spend FROM orders "
                    + "WHERE customer_id = ? AND status = 'COMPLETED'", cid).get(0);
            LinkedHashMap<String, Object> travel = Sql.rows(ignite,
                "SELECT COUNT(DISTINCT CASE WHEN b.status = 'CONFIRMED' THEN b.booking_id END) AS bookings, "
                    + "COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.fare ELSE 0 END), 0) AS travel_spend, "
                    + "COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.miles_earned ELSE 0 END), 0) AS miles "
                    + "FROM booking b LEFT JOIN booking_segment s ON s.booking_id = b.booking_id AND s.customer_id = b.customer_id "
                    + "WHERE b.customer_id = ?", cid).get(0);

            List<LinkedHashMap<String, Object>> upcoming = Sql.rows(ignite,
                "SELECT " + TripsTask.COLS + TripsTask.FROM + "WHERE b.customer_id = ? AND s.status <> 'CANCELLED' AND f.dep_time >= ? "
                    + "ORDER BY f.dep_time, s.segment_id LIMIT 5", cid, now);
            List<LinkedHashMap<String, Object>> atRisk = Sql.rows(ignite,
                "SELECT " + TripsTask.COLS + TripsTask.FROM + "WHERE b.customer_id = ? AND s.status IN " + Travel.ACTIVE_SEGMENTS
                    + " AND f.dep_time >= ? AND f.status IN ('DELAYED', 'CANCELLED') ORDER BY f.dep_time, s.segment_id", cid, now);

            BigDecimal retailSpend = (BigDecimal) retail.get("retail_spend"), travelSpend = (BigDecimal) travel.get("travel_spend");
            BigDecimal total = retailSpend.add(travelSpend);
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("loyalty_tier", profile.get(0).get("loyalty_tier"));
            value.put("loyalty_points", profile.get(0).get("loyalty_points"));
            value.put("retail_spend", retailSpend);
            value.put("travel_spend", travelSpend);
            value.put("lifetime_value", total);
            value.put("travel_share_pct", total.signum() == 0 ? BigDecimal.ZERO
                : travelSpend.multiply(BigDecimal.valueOf(100)).divide(total, 1, java.math.RoundingMode.HALF_UP));

            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("profile", new ArrayList<Object>(profile));
            out.put("retail", Loyalty.list(retail));
            out.put("travel", Loyalty.list(travel));
            out.put("upcoming_trips", new ArrayList<Object>(upcoming));
            out.put("at_risk_trips", new ArrayList<Object>(atRisk));
            out.put("value", Loyalty.list(value));
            return out;
        }
    }
}
