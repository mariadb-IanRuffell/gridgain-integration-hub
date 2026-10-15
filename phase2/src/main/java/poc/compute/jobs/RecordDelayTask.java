package poc.compute.jobs;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionConcurrency;
import org.apache.ignite.transactions.TransactionIsolation;

/**
 * Replaces the Oracle procedures sp_record_delay and sp_cancel_flight: change a flight's status and write a flight event in
 * one transaction, and report how many confirmed bookings are affected.
 * Argument map: flight_id, minutes (delay only), reason (optional), cancel (Boolean: true = cancel the flight).
 * Errors: 20120 delay out of range, 20103 flight not found, 20121 flight already operated or cancelled.
 */
public class RecordDelayTask extends LocalTask<Map<String, Object>> {
    @Override
    ComputeJob job(Map<String, Object> a) {
        return new Job(((Number) a.get("flight_id")).intValue(), a.get("minutes") == null ? 0 : ((Number) a.get("minutes")).intValue(),
            (String) a.get("reason"), Boolean.TRUE.equals(a.get("cancel")));
    }

    static class Job extends AffinityTask.CustomerJob {
        private static final long ID_BASE = 1_000_000_000L;

        private final int fid, minutes;
        private final String reason;
        private final boolean cancel;

        Job(int fid, int minutes, String reason, boolean cancel) {
            this.fid = fid;
            this.minutes = minutes;
            this.reason = reason;
            this.cancel = cancel;
        }

        @Override
        public Object execute() {
            if (!cancel && (minutes < 1 || minutes > 1440)) return AffinityTask.fail(-1, 20120, "Delay must be between 1 and 1440 minutes");
            IgniteCache<Integer, BinaryObject> flights = ignite.<Integer, BinaryObject>cache("flight").withKeepBinary();
            IgniteCache<Integer, BinaryObject> events = ignite.<Integer, BinaryObject>cache("flight_event").withKeepBinary();
            int eventId = (int) ignite.atomicSequence("seq_flight_event_id", ID_BASE, true).incrementAndGet();

            try (Transaction tx = ignite.transactions().txStart(
                    TransactionConcurrency.PESSIMISTIC, TransactionIsolation.REPEATABLE_READ, 10_000, 0)) {
                BinaryObject f = flights.get(fid);                              // locks the flight
                if (f == null) return AffinityTask.fail(-1, 20103, "Flight not found");
                String st = f.field("STATUS");
                if ("DEPARTED".equals(st) || "ARRIVED".equals(st) || "CANCELLED".equals(st))
                    return AffinityTask.fail(-1, 20121, "Flight has already operated or is cancelled");

                String suffix = reason != null ? ": " + reason : "";
                flights.put(fid, cancel ? f.toBuilder().setField("STATUS", "CANCELLED").build()
                    : f.toBuilder().setField("STATUS", "DELAYED").setField("DELAY_MINUTES", minutes).build());
                events.put(eventId, ignite.binary().builder("FlightEvent").setField("FLIGHT_ID", fid)
                    .setField("EVENT_TYPE", cancel ? "CANCEL" : "DELAY")
                    .setField("DETAIL", cancel ? "Flight cancelled" + suffix : "Delayed " + minutes + " min" + suffix)
                    .setField("EVENT_TIME", new Timestamp(System.currentTimeMillis())).build());
                tx.commit();
            }
            // distributed count over the customers' partitions (bookings and segments are colocated)
            Object affected = Sql.scalar(ignite, "SELECT COUNT(*) FROM booking_segment s JOIN booking b ON b.booking_id = s.booking_id "
                + "AND b.customer_id = s.customer_id WHERE s.flight_id = ? AND s.status IN " + Travel.ACTIVE_SEGMENTS
                + " AND b.status = 'CONFIRMED'", fid);
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("flight_id", fid);
            out.put("status", cancel ? "CANCELLED" : "DELAYED");
            out.put("affected_bookings", ((Number) affected).longValue());
            return out;
        }
    }
}
