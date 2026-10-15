package poc.compute.jobs;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.ignite.compute.ComputeJob;

/**
 * Replaces the Oracle procedure sp_customer_trips: upcoming trips, past trips and travel statistics for one customer.
 * Runs on the node that owns the customer's partition (bookings and segments are colocated on customer_id).
 */
public class TripsTask extends AffinityTask<Integer> {
    @Override
    int customerId(Integer cid) {
        return cid;
    }

    @Override
    ComputeJob job(Integer cid) {
        return new Job(cid);
    }

    static final String COLS = "b.booking_ref AS booking_ref, f.flight_no AS flight_no, f.origin AS origin, f.destination AS destination, "
        + "f.dep_time AS dep_time, f.arr_time AS arr_time, s.cabin AS cabin, s.seat_no AS seat_no, "
        + "s.status AS segment_status, f.status AS flight_status, f.delay_minutes AS delay_minutes";
    static final String FROM = " FROM booking b JOIN booking_segment s ON s.booking_id = b.booking_id AND s.customer_id = b.customer_id "
        + "JOIN flight f ON f.flight_id = s.flight_id ";

    static class Job extends CustomerJob {
        private final int cid;

        Job(int cid) {
            this.cid = cid;
        }

        @Override
        public Object execute() {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            List<LinkedHashMap<String, Object>> upcoming = Sql.rows(ignite,
                "SELECT " + COLS + FROM + "WHERE b.customer_id = ? AND s.status <> 'CANCELLED' AND f.dep_time >= ? "
                    + "ORDER BY f.dep_time, s.segment_id LIMIT 10", cid, now);
            List<LinkedHashMap<String, Object>> past = Sql.rows(ignite,
                "SELECT " + COLS + FROM + "WHERE b.customer_id = ? AND s.status <> 'CANCELLED' AND f.dep_time < ? "
                    + "ORDER BY f.dep_time DESC, s.segment_id DESC LIMIT 10", cid, now);
            List<LinkedHashMap<String, Object>> stats = Sql.rows(ignite,
                "SELECT COUNT(DISTINCT CASE WHEN b.status = 'CONFIRMED' THEN b.booking_id END) AS bookings, "
                    + "COUNT(DISTINCT CASE WHEN b.status = 'CANCELLED' THEN b.booking_id END) AS cancelled_bookings, "
                    + "COALESCE(SUM(CASE WHEN s.status = 'FLOWN' THEN 1 ELSE 0 END), 0) AS segments_flown, "
                    + "COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.miles_earned ELSE 0 END), 0) AS total_miles, "
                    + "COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.fare ELSE 0 END), 0) AS total_spent, "
                    + "MAX(CASE WHEN s.status = 'FLOWN' THEN f.dep_time END) AS last_flown "
                    + "FROM booking b LEFT JOIN booking_segment s ON s.booking_id = b.booking_id AND s.customer_id = b.customer_id "
                    + "LEFT JOIN flight f ON f.flight_id = s.flight_id WHERE b.customer_id = ?", cid);
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("upcoming", new ArrayList<Object>(upcoming));
            out.put("past", new ArrayList<Object>(past));
            out.put("stats", new ArrayList<Object>(stats));
            return out;
        }
    }
}
