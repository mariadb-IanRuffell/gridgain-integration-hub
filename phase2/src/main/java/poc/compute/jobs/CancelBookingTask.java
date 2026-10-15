package poc.compute.jobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionConcurrency;
import org.apache.ignite.transactions.TransactionIsolation;

/**
 * Replaces the Oracle procedure sp_cancel_booking: cancel every future segment, release the seats and work out the refund
 * (> 72 h before departure 90%, > 24 h 50%, otherwise nothing), all in one cache transaction on the owning node.
 * Argument: booking_id. Errors as the procedure: 20110 not found, 20111 not active, 20112 nothing cancellable.
 */
public class CancelBookingTask extends AffinityTask<Integer> {
    @Override
    int customerId(Integer bookingId) {
        List<LinkedHashMap<String, Object>> r = Sql.rows(ignite(), "SELECT customer_id FROM booking WHERE booking_id = ?", bookingId);
        return r.isEmpty() ? -1 : ((Number) r.get(0).get("customer_id")).intValue();
    }

    @Override
    ComputeJob job(Integer bookingId) {
        return new Job(bookingId, customerId(bookingId));
    }

    /** Unknown booking: there is no owner to route to, so run locally and let the job answer 20110. */
    @Override
    public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, Integer bookingId) {
        int cid = customerId(bookingId);
        ClusterNode node = cid < 0 ? ignite().cluster().localNode() : ignite().affinity("booking").mapKeyToNode(cid);
        return Collections.singletonMap(new Job(bookingId, cid), node);
    }

    static class Job extends CustomerJob {
        private final int bookingId, cid;

        Job(int bookingId, int cid) {
            this.bookingId = bookingId;
            this.cid = cid;
        }

        @Override
        public Object execute() {
            if (cid < 0) return fail(-1, 20110, "Booking not found");
            Timestamp now = new Timestamp(System.currentTimeMillis());
            IgniteCache<BinaryObject, BinaryObject> bookings = ignite.<BinaryObject, BinaryObject>cache("booking").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> segments = ignite.<BinaryObject, BinaryObject>cache("booking_segment").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> inventory = ignite.<BinaryObject, BinaryObject>cache("flight_inventory").withKeepBinary();

            try (Transaction tx = ignite.transactions().txStart(
                    TransactionConcurrency.PESSIMISTIC, TransactionIsolation.REPEATABLE_READ, 10_000, 0)) {
                BinaryObject bKey = Travel.key(ignite, "BookingKey", "BOOKING_ID", bookingId, cid);
                BinaryObject booking = bookings.get(bKey);                       // locks the booking
                if (booking == null) return fail(-1, 20110, "Booking not found");
                if (!"CONFIRMED".equals(booking.<String>field("STATUS"))) return fail(-1, 20111, "Booking is not active");

                // all segments of the booking, so we know whether any live one remains afterwards
                List<LinkedHashMap<String, Object>> all = Sql.rows(ignite,
                    "SELECT s.segment_id, s.flight_id, s.cabin, s.fare, s.status, f.dep_time FROM booking_segment s "
                        + "JOIN flight f ON f.flight_id = s.flight_id WHERE s.booking_id = ? AND s.customer_id = ? ORDER BY s.segment_id",
                    bookingId, cid);
                BigDecimal refund = BigDecimal.ZERO;
                int cancelled = 0, stillLive = 0;
                List<Integer> cancelIds = new ArrayList<>();
                for (Map<String, Object> s : all) {
                    String st = (String) s.get("status");
                    Timestamp dep = (Timestamp) s.get("dep_time");
                    boolean cancellable = ("TICKETED".equals(st) || "CHECKED_IN".equals(st)) && dep.after(now);
                    if (!cancellable) { if (!"CANCELLED".equals(st)) stillLive++; continue; }
                    double hours = (dep.getTime() - now.getTime()) / 3_600_000.0;
                    refund = refund.add(((BigDecimal) s.get("fare")).multiply(Travel.refundPct(hours)).setScale(2, RoundingMode.HALF_UP));
                    int segId = ((Number) s.get("segment_id")).intValue();
                    BinaryObject sKey = Travel.key(ignite, "BookingSegmentKey", "SEGMENT_ID", segId, cid);
                    segments.put(sKey, segments.get(sKey).toBuilder().setField("STATUS", "CANCELLED").build());
                    BinaryObject iKey = Travel.inventoryKey(ignite, ((Number) s.get("flight_id")).intValue(), (String) s.get("cabin"));
                    BinaryObject inv = inventory.get(iKey);
                    inventory.put(iKey, inv.toBuilder().setField("SEATS_AVAILABLE", inv.<Integer>field("SEATS_AVAILABLE") + 1).build());
                    cancelIds.add(segId);
                    cancelled++;
                }
                if (cancelled == 0) return fail(-1, 20112, "No cancellable segments");

                BigDecimal refunded = booking.<BigDecimal>field("REFUNDED").add(refund);
                bookings.put(bKey, booking.toBuilder().setField("STATUS", stillLive == 0 ? "CANCELLED" : "CONFIRMED")
                    .setField("REFUNDED", refunded.setScale(2, RoundingMode.HALF_UP)).build());
                tx.commit();

                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                out.put("rc", 0);
                out.put("booking_id", bookingId);
                out.put("refund", refund);
                out.put("segments_cancelled", cancelled);
                out.put("booking_status", stillLive == 0 ? "CANCELLED" : "CONFIRMED");
                return out;
            }
        }
    }
}
