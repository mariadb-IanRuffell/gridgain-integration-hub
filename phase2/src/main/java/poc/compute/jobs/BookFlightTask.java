package poc.compute.jobs;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionConcurrency;
import org.apache.ignite.transactions.TransactionIsolation;

/**
 * Replaces the Oracle procedure sp_book_flight: reserve one seat and create the booking and its segment in ONE cache
 * transaction on the node owning the customer. Argument map: customer_id, flight_id, cabin, passenger, channel (optional).
 *
 * Same validation order and error numbers as the procedure: 20104 passenger, 20105 cabin, 20103 flight not found,
 * 20106 flight not open, 20107 cabin sold out. Ids come from GridGain sequences starting at 1,000,000,000 so they cannot
 * collide with Oracle identity values that CDC is still replicating.
 */
public class BookFlightTask extends AffinityTask<Map<String, Object>> {
    @Override
    int customerId(Map<String, Object> arg) {
        return ((Number) arg.get("customer_id")).intValue();
    }

    @Override
    ComputeJob job(Map<String, Object> arg) {
        return new Job(customerId(arg), ((Number) arg.get("flight_id")).intValue(), (String) arg.get("cabin"),
            (String) arg.get("passenger"), (String) arg.getOrDefault("channel", "WEB"));
    }

    static class Job extends CustomerJob {
        private static final long ID_BASE = 1_000_000_000L;

        private final int cid, fid;
        private final String cabin, passenger, channel;

        Job(int cid, int fid, String cabin, String passenger, String channel) {
            this.cid = cid;
            this.fid = fid;
            this.cabin = cabin;
            this.passenger = passenger;
            this.channel = channel == null ? "WEB" : channel;
        }

        @Override
        public Object execute() {
            if (passenger == null || passenger.trim().isEmpty()) return fail(-1, 20104, "Passenger name required");
            if (!Travel.validCabin(cabin)) return fail(-1, 20105, "Invalid cabin");

            Timestamp now = new Timestamp(System.currentTimeMillis());
            IgniteCache<Integer, BinaryObject> flights = ignite.<Integer, BinaryObject>cache("flight").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> inventory = ignite.<BinaryObject, BinaryObject>cache("flight_inventory").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> bookings = ignite.<BinaryObject, BinaryObject>cache("booking").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> segments = ignite.<BinaryObject, BinaryObject>cache("booking_segment").withKeepBinary();

            BinaryObject flight = flights.get(fid);
            if (flight == null) return fail(-1, 20103, "Flight not found");
            String status = flight.field("STATUS");
            Timestamp dep = flight.field("DEP_TIME");
            if (!("SCHEDULED".equals(status) || "DELAYED".equals(status)) || !dep.after(now))
                return fail(-1, 20106, "Flight is not open for booking");

            // Ids are allocated BEFORE the transaction (first use of a sequence starts a system cache, not allowed inside one).
            int bookingId = (int) ignite.atomicSequence("seq_booking_id", ID_BASE, true).incrementAndGet();
            int segmentId = (int) ignite.atomicSequence("seq_segment_id", ID_BASE, true).incrementAndGet();
            String ref = Travel.randomRef();
            while (!Sql.rows(ignite, "SELECT 1 FROM booking WHERE booking_ref = ?", ref).isEmpty()) ref = Travel.randomRef();

            try (Transaction tx = ignite.transactions().txStart(
                    TransactionConcurrency.PESSIMISTIC, TransactionIsolation.REPEATABLE_READ, 10_000, 0)) {
                BinaryObject invKey = Travel.inventoryKey(ignite, fid, cabin);
                BinaryObject inv = inventory.get(invKey);               // locks the seat inventory row
                if (inv == null || inv.<Integer>field("SEATS_AVAILABLE") <= 0) return fail(-1, 20107, "Cabin sold out");

                BigDecimal fare = inv.field("BASE_FARE");
                int n = inv.<Integer>field("NEXT_SEAT");
                inventory.put(invKey, inv.toBuilder().setField("SEATS_AVAILABLE", inv.<Integer>field("SEATS_AVAILABLE") - 1)
                    .setField("NEXT_SEAT", n + 1).build());

                bookings.put(Travel.key(ignite, "BookingKey", "BOOKING_ID", bookingId, cid), ignite.binary().builder("Booking")
                    .setField("BOOKING_REF", ref).setField("STATUS", "CONFIRMED").setField("BOOKED_AT", now)
                    .setField("CHANNEL", channel).setField("TOTAL_FARE", fare).setField("REFUNDED", BigDecimal.ZERO.setScale(2))
                    .setField("CURRENCY", "AED").build());
                segments.put(Travel.key(ignite, "BookingSegmentKey", "SEGMENT_ID", segmentId, cid), ignite.binary().builder("BookingSegment")
                    .setField("BOOKING_ID", bookingId).setField("FLIGHT_ID", fid).setField("CABIN", cabin)
                    .setField("SEAT_NO", Travel.seatNo(cabin, n)).setField("PASSENGER_NAME", passenger).setField("FARE", fare)
                    .setField("STATUS", "TICKETED").setField("MILES_EARNED", Travel.miles(flight.<Integer>field("DISTANCE_KM"), cabin)).build());
                tx.commit();

                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                out.put("rc", 0);
                out.put("booking_id", bookingId);
                out.put("booking_ref", ref);
                out.put("customer_id", cid);
                out.put("flight_id", fid);
                out.put("cabin", cabin);
                out.put("seat_no", Travel.seatNo(cabin, n));
                out.put("fare", fare);
                out.put("miles_earned", Travel.miles(flight.<Integer>field("DISTANCE_KM"), cabin));
                return out;
            }
        }
    }
}
