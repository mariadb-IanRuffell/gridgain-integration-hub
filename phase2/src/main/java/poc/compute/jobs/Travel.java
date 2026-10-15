package poc.compute.jobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import org.apache.ignite.Ignite;
import org.apache.ignite.binary.BinaryObject;

/** Rules shared by the travel jobs: the PL/SQL equivalents are fn_seat_no, fn_miles_factor and the refund CASE in sp_cancel_booking. */
final class Travel {
    private static final Random RND = new Random();

    private Travel() {}

    /** Seat label from the per-flight-cabin issue counter: rows start at 1 (first), 3 (business), 12 (economy), 6 seats per row. */
    static String seatNo(String cabin, int n) {
        int base = "FIRST".equals(cabin) ? 1 : "BUSINESS".equals(cabin) ? 3 : 12;
        int idx = (n - 1) % 6;
        return (base + (n - 1) / 6) + "ABCDEF".substring(idx, idx + 1);
    }

    static BigDecimal milesFactor(String cabin) {
        return "FIRST".equals(cabin) ? new BigDecimal("2") : "BUSINESS".equals(cabin) ? new BigDecimal("1.5") : BigDecimal.ONE;
    }

    static int miles(int distanceKm, String cabin) {
        return BigDecimal.valueOf(distanceKm).multiply(milesFactor(cabin)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** Refund share by hours to departure: > 72 h 90%, > 24 h 50%, otherwise nothing. */
    static BigDecimal refundPct(double hours) {
        return hours > 72 ? new BigDecimal("0.9") : hours > 24 ? new BigDecimal("0.5") : BigDecimal.ZERO;
    }

    static boolean validCabin(String c) {
        return "ECONOMY".equals(c) || "BUSINESS".equals(c) || "FIRST".equals(c);
    }

    static String randomRef() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) b.append((char) ('A' + RND.nextInt(26)));
        return b.toString();
    }

    /** Composite SQL primary key (surrogate id + customer_id affinity key) as a BinaryObject. */
    static BinaryObject key(Ignite ignite, String type, String idField, int id, int customerId) {
        return ignite.binary().builder(type).setField(idField, id).setField("CUSTOMER_ID", customerId).build();
    }

    static BinaryObject inventoryKey(Ignite ignite, int flightId, String cabin) {
        return ignite.binary().builder("FlightInventoryKey").setField("FLIGHT_ID", flightId).setField("CABIN", cabin).build();
    }

    /** Segment statuses that are still live (can be cancelled, or are affected by a disruption). */
    static final String ACTIVE_SEGMENTS = "('TICKETED', 'CHECKED_IN')";
}
