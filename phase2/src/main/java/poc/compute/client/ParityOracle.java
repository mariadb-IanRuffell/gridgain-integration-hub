package poc.compute.client;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.client.ClientCompute;
import org.apache.ignite.client.IgniteClient;

/**
 * Side-by-side parity for the ORACLE travel system: run each PL/SQL procedure and its GridGain compute job on identical inputs
 * from an identical starting state, and compare the outcomes value by value. Oracle over JDBC, GridGain over the thin client and
 * thin JDBC. Writes to BOTH systems: stop the Oracle CDC reader first and resync afterwards (the run prints how).
 */
final class ParityOracle {
    private static final String PKG = Main.PKG;

    private final ClientCompute compute;
    private Connection ora, gg;
    private int passed, failed;
    private int oraTestSegment, ggTestSegment;   // the segment each system created in the book-flight test (ids differ by design)

    ParityOracle(IgniteClient client) {
        this.compute = client.compute().withTimeout(120_000);
    }

    boolean run() throws Exception {
        ora = DriverManager.getConnection(ParityMariaDb.env("ORA_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"),
            ParityMariaDb.env("ORA_USER", "travel"), ParityMariaDb.env("ORA_PASSWORD", "travel"));
        gg = DriverManager.getConnection(ParityMariaDb.env("GG_JDBC", "jdbc:ignite:thin://127.0.0.1:10800,127.0.0.1:10801,127.0.0.1:10802"));
        try {
            List<List<Object>> o = new ArrayList<>(), g = new ArrayList<>();
            for (String q : SNAPSHOT) { o.addAll(ora(q)); g.addAll(gg(q)); }
            String pre = ParityMariaDb.compareRows(o, g);
            if (pre != null) {
                check("GridGain in sync with Oracle before the test (stop gg-cdc-oracle, then CDC_SOURCE=oracle ./run.sh init)", false, pre);
                return false;
            }
            trips();
            bookFlight();
            cancelBooking();
            flightOps();
            disruptions();
            errors();
        } finally {
            ora.close();
            gg.close();
        }
        System.out.printf("%n%s: %d checks passed, %d failed%n", failed == 0 ? "PARITY OK" : "PARITY FAILED", passed, failed);
        System.out.println("   NOTE: Oracle and GridGain now hold different test rows. Resync: CDC_SOURCE=oracle ../cdc/run.sh init, then start gg-cdc-oracle");
        return failed == 0;
    }

    /** Checkpoints that must match before anything is changed (separate scalar queries: Oracle and GridGain parse a combined one differently). */
    private static final String[] SNAPSHOT = {
        "select count(*) from booking", "select count(*) from booking_segment", "select sum(seats_available) from flight_inventory",
        "select sum(next_seat) from flight_inventory", "select count(*) from flight_event", "select count(*) from compensation"};

    // ------------------------------------------------------------------ sp_customer_trips

    private void trips() throws Exception {
        System.out.println("== sp_customer_trips vs TripsTask (every customer with bookings, plus one without)");
        List<Integer> ids = ParityMariaDb.ints(ora, "select distinct customer_id from booking order by 1");
        ids.add(999999);
        int bad = 0;
        for (int cid : ids) {
            List<List<List<Object>>> exp = new ArrayList<>();
            try (CallableStatement cs = ora.prepareCall("{call sp_customer_trips(?, ?, ?, ?)}")) {
                cs.setInt(1, cid);
                for (int i = 2; i <= 4; i++) cs.registerOutParameter(i, Types.REF_CURSOR);
                cs.execute();
                for (int i = 2; i <= 4; i++) try (ResultSet rs = cs.getObject(i, ResultSet.class)) { exp.add(read(rs)); }
            }
            Map<String, Object> job = compute.execute(PKG + "TripsTask", cid);
            String[] sets = {"upcoming", "past", "stats"};
            for (int s = 0; s < 3; s++) {
                String diff = ParityMariaDb.compareRows(exp.get(s), ParityMariaDb.rows((List<?>) job.get(sets[s])));
                if (diff != null && bad++ < 4) System.out.println("   customer " + cid + " set '" + sets[s] + "': " + diff);
            }
        }
        check("sp_customer_trips parity over " + ids.size() + " customers x 3 result sets", bad == 0, bad + " differences");
    }

    // ------------------------------------------------------------------ sp_book_flight

    private void bookFlight() throws Exception {
        System.out.println("== sp_book_flight vs BookFlightTask");
        int cid = 12;
        int fid = ParityMariaDb.scalarInt(ora, "select min(f.flight_id) from flight f join flight_inventory i on i.flight_id = f.flight_id and i.cabin = 'BUSINESS' "
            + "where f.status = 'SCHEDULED' and f.dep_time > sys_extract_utc(systimestamp) + interval '5' day and i.seats_available > 0");
        System.out.println("   customer " + cid + ", flight " + fid + ", BUSINESS");
        String inv = "select seats_available, next_seat, base_fare from flight_inventory where flight_id = " + fid + " and cabin = 'BUSINESS'";
        check("starting inventory identical", ParityMariaDb.compareRows(ora(inv), gg(inv)) == null, "");

        long oraBooking; String oraRef;
        try (CallableStatement cs = ora.prepareCall("{call sp_book_flight(?, ?, ?, ?, ?, ?, ?)}")) {
            cs.setInt(1, cid); cs.setInt(2, fid); cs.setString(3, "BUSINESS"); cs.setString(4, "Parity Passenger"); cs.setString(5, "APP");
            cs.registerOutParameter(6, Types.NUMERIC); cs.registerOutParameter(7, Types.VARCHAR);
            cs.execute();
            oraBooking = cs.getLong(6); oraRef = cs.getString(7);
        }
        Map<String, Object> args = new HashMap<>();
        args.put("customer_id", cid); args.put("flight_id", fid); args.put("cabin", "BUSINESS"); args.put("passenger", "Parity Passenger"); args.put("channel", "APP");
        Map<String, Object> job = compute.execute(PKG + "BookFlightTask", args);
        int ggBooking = (Integer) job.get("booking_id");

        String oraSeg = "select s.seat_no, s.fare, s.miles_earned, s.status, s.cabin, s.passenger_name, b.status, b.total_fare, b.channel, b.currency "
            + "from booking_segment s join booking b on b.booking_id = s.booking_id where b.booking_id = ";
        check("booking, segment, seat, fare and miles identical", ParityMariaDb.compareRows(ora(oraSeg + oraBooking), gg(oraSeg + ggBooking)) == null,
            ora(oraSeg + oraBooking) + " vs " + gg(oraSeg + ggBooking));
        oraTestSegment = ParityMariaDb.scalarInt(ora, "select segment_id from booking_segment where booking_id = " + oraBooking);
        ggTestSegment = ParityMariaDb.scalarInt(gg, "select segment_id from booking_segment where booking_id = " + ggBooking);
        check("booking_ref is 6 letters", ((String) job.get("booking_ref")).length() == 6 && oraRef.length() == 6, "");
        check("seat inventory identical afterwards", ParityMariaDb.compareRows(ora(inv), gg(inv)) == null, ora(inv) + " vs " + gg(inv));
    }

    // ------------------------------------------------------------------ sp_cancel_booking

    private void cancelBooking() throws Exception {
        System.out.println("== sp_cancel_booking vs CancelBookingTask");
        // (a) every segment future and ticketed: full cancellation
        int full = ParityMariaDb.scalarInt(ora, "select min(b.booking_id) from booking b where b.status = 'CONFIRMED' and exists (select 1 from booking_segment where booking_id = b.booking_id) "
            + "and not exists (select 1 from booking_segment s join flight f on f.flight_id = s.flight_id where s.booking_id = b.booking_id "
            + "and (s.status <> 'TICKETED' or f.dep_time < sys_extract_utc(systimestamp) + interval '100' hour)) and b.booking_id < 1000000");
        cancelCase("full cancellation of booking " + full, full);
        // (b) partly flown: one flown segment stays, the future one is cancelled, booking remains CONFIRMED
        int partial = ParityMariaDb.scalarInt(ora, "select min(b.booking_id) from booking b where b.status = 'CONFIRMED' "
            + "and exists (select 1 from booking_segment where booking_id = b.booking_id and status = 'FLOWN') "
            + "and exists (select 1 from booking_segment s join flight f on f.flight_id = s.flight_id where s.booking_id = b.booking_id "
            + "and s.status = 'TICKETED' and f.dep_time > sys_extract_utc(systimestamp) + interval '100' hour) and b.booking_id < 1000000");
        cancelCase("partial cancellation of booking " + partial, partial);
    }

    private void cancelCase(String what, int bookingId) throws Exception {
        String state = "select b.status, b.refunded, (select listagg(s.status, ',') within group (order by s.segment_id) from booking_segment s where s.booking_id = b.booking_id) "
            + "from booking b where b.booking_id = " + bookingId;
        String invSum = "select sum(seats_available) from flight_inventory";
        double refund;
        try (CallableStatement cs = ora.prepareCall("{call sp_cancel_booking(?, ?)}")) {
            cs.setInt(1, bookingId); cs.registerOutParameter(2, Types.NUMERIC);
            cs.execute();
            refund = cs.getBigDecimal(2).doubleValue();
        }
        Map<String, Object> job = compute.execute(PKG + "CancelBookingTask", bookingId);
        check(what + ": refund " + refund, ParityMariaDb.same(BigDecimal.valueOf(refund), job.get("refund")), refund + " vs " + job.get("refund"));
        // GridGain has no listagg; assemble the same string from the segment rows
        String ggState = "select b.status, b.refunded from booking b where b.booking_id = " + bookingId;
        List<List<Object>> o = ora(state), g = gg(ggState);
        String oSegs = String.valueOf(o.get(0).get(2));
        StringBuilder gs = new StringBuilder();
        for (List<Object> r : gg("select status from booking_segment where booking_id = " + bookingId + " order by segment_id")) gs.append(gs.length() > 0 ? "," : "").append(r.get(0));
        check(what + ": booking status, refunded and segment statuses identical",
            ParityMariaDb.same(o.get(0).get(0), g.get(0).get(0)) && ParityMariaDb.same(o.get(0).get(1), g.get(0).get(1)) && oSegs.equals(gs.toString()),
            o + " vs " + g + " segs " + gs);
        check(what + ": seats released identically", ParityMariaDb.compareRows(ora(invSum), gg(invSum)) == null, ora(invSum) + " vs " + gg(invSum));
    }

    // ------------------------------------------------------------------ sp_record_delay / sp_cancel_flight

    private void flightOps() throws Exception {
        System.out.println("== sp_record_delay / sp_cancel_flight vs RecordDelayTask");
        List<Integer> flights = ParityMariaDb.ints(ora, "select flight_id from (select f.flight_id from flight f where f.status = 'SCHEDULED' "
            + "and f.dep_time > sys_extract_utc(systimestamp) + interval '8' day and exists (select 1 from booking_segment s where s.flight_id = f.flight_id "
            + "and s.status = 'TICKETED') order by f.flight_id) fetch first 2 rows only");
        int delayed = flights.get(0), cancelled = flights.get(1);
        long oAff;
        try (CallableStatement cs = ora.prepareCall("{call sp_record_delay(?, ?, ?, ?)}")) {
            cs.setInt(1, delayed); cs.setInt(2, 400); cs.setString(3, "parity test"); cs.registerOutParameter(4, Types.NUMERIC);
            cs.execute(); oAff = cs.getLong(4);
        }
        Map<String, Object> a = new HashMap<>();
        a.put("flight_id", delayed); a.put("minutes", 400); a.put("reason", "parity test"); a.put("cancel", false);
        Map<String, Object> job = compute.execute(PKG + "RecordDelayTask", a);
        check("delay flight " + delayed + ": affected bookings " + oAff, oAff == ((Number) job.get("affected_bookings")).longValue(), oAff + " vs " + job.get("affected_bookings"));
        String fl = "select status, delay_minutes from flight where flight_id = " + delayed;
        String ev = "select event_type, detail from flight_event where flight_id = " + delayed + " and detail like '%parity test%'";
        check("flight status and delay identical", ParityMariaDb.compareRows(ora(fl), gg(fl)) == null, ora(fl) + " vs " + gg(fl));
        check("flight event type and text identical", ParityMariaDb.compareRows(ora(ev), gg(ev)) == null, ora(ev) + " vs " + gg(ev));

        try (CallableStatement cs = ora.prepareCall("{call sp_cancel_flight(?, ?, ?)}")) {
            cs.setInt(1, cancelled); cs.setString(2, "parity test"); cs.registerOutParameter(3, Types.NUMERIC);
            cs.execute(); oAff = cs.getLong(3);
        }
        a = new HashMap<>();
        a.put("flight_id", cancelled); a.put("reason", "parity test"); a.put("cancel", true);
        job = compute.execute(PKG + "RecordDelayTask", a);
        check("cancel flight " + cancelled + ": affected bookings " + oAff, oAff == ((Number) job.get("affected_bookings")).longValue(), oAff + " vs " + job.get("affected_bookings"));
        fl = "select status, delay_minutes from flight where flight_id = " + cancelled;
        ev = "select event_type, detail from flight_event where flight_id = " + cancelled + " and detail like '%parity test%'";
        check("cancelled flight status and event identical", ParityMariaDb.compareRows(ora(fl), gg(fl)) == null && ParityMariaDb.compareRows(ora(ev), gg(ev)) == null,
            "" + ora(fl) + ora(ev) + " vs " + gg(fl) + gg(ev));
    }

    // ------------------------------------------------------------------ sp_process_disruptions

    private void disruptions() throws Exception {
        System.out.println("== sp_process_disruptions (cursor) vs ProcessDisruptionsTask (per node)");
        long asOf = System.currentTimeMillis() / 1000 * 1000;
        List<List<Object>> exp = new ArrayList<>();
        try (CallableStatement cs = ora.prepareCall("{call sp_process_disruptions(?, ?, ?)}")) {
            cs.setInt(1, 180); cs.setTimestamp(2, new Timestamp(asOf)); cs.registerOutParameter(3, Types.REF_CURSOR);
            cs.execute();
            try (ResultSet rs = cs.getObject(3, ResultSet.class)) { exp = read(rs); }
        }
        Map<String, Object> a = new HashMap<>();
        a.put("min_delay", 180); a.put("as_of", asOf);
        List<?> job = compute.execute(PKG + "ProcessDisruptionsTask", a);
        check("summary by reason (reason, segments, total_amount)", ParityMariaDb.compareRows(exp, ParityMariaDb.rows(job)) == null, exp + " vs " + ParityMariaDb.rows(job));
        // every seeded segment must match exactly, id included; the one test booking each system made has a different id by design
        String cols = "select segment_id, amount, reason, created_at from compensation where segment_id <> ";
        List<List<Object>> o = ora(cols + oraTestSegment + " order by segment_id"), g = gg(cols + ggTestSegment + " order by segment_id");
        String diff = ParityMariaDb.compareRows(o, g);
        check("compensation identical for the " + o.size() + " seeded segments (id, amount, reason, created_at)", diff == null, String.valueOf(diff));
        String t = "select amount, reason, created_at from compensation where segment_id = ";
        check("compensation for the test booking identical apart from its id (amount, reason, created_at)",
            ParityMariaDb.compareRows(ora(t + oraTestSegment), gg(t + ggTestSegment)) == null, ora(t + oraTestSegment) + " vs " + gg(t + ggTestSegment));
    }

    // ------------------------------------------------------------------ error behaviour

    private void errors() throws Exception {
        System.out.println("== error behaviour: same error number for the same bad input");
        int open = ParityMariaDb.scalarInt(ora, "select min(flight_id) from flight where status = 'SCHEDULED' and dep_time > sys_extract_utc(systimestamp) + interval '20' day");
        int past = ParityMariaDb.scalarInt(ora, "select min(flight_id) from flight where status = 'ARRIVED'");
        int cancelledFlight = ParityMariaDb.scalarInt(ora, "select min(flight_id) from flight where status = 'CANCELLED'");
        int cancelledBooking = ParityMariaDb.scalarInt(ora, "select min(booking_id) from booking where status = 'CANCELLED'");

        bookError("book: unknown flight", 20103, 12, 999999, "ECONOMY", "A Passenger");
        bookError("book: invalid cabin", 20105, 12, open, "PREMIUM", "A Passenger");
        bookError("book: blank passenger", 20104, 12, open, "ECONOMY", "  ");
        bookError("book: flight already operated", 20106, 12, past, "ECONOMY", "A Passenger");
        // sold out: empty the same cabin on both sides
        ora.createStatement().executeUpdate("update flight_inventory set seats_available = 0 where flight_id = " + open + " and cabin = 'FIRST'");
        gg.createStatement().executeUpdate("update flight_inventory set seats_available = 0 where flight_id = " + open + " and cabin = 'FIRST'");
        bookError("book: cabin sold out", 20107, 12, open, "FIRST", "A Passenger");

        cancelError("cancel booking: not found", 20110, 999999999);
        cancelError("cancel booking: already cancelled", 20111, cancelledBooking);
        flightError("delay: out of range", 20120, open, 0, false);
        flightError("delay: unknown flight", 20103, 999999, 30, false);
        flightError("delay: flight is cancelled", 20121, cancelledFlight, 30, false);
        flightError("cancel flight: already cancelled", 20121, cancelledFlight, 0, true);
    }

    private void bookError(String name, int expected, int cid, int fid, String cabin, String pax) throws Exception {
        int oErr = 0;
        try (CallableStatement cs = ora.prepareCall("{call sp_book_flight(?, ?, ?, ?, ?, ?, ?)}")) {
            cs.setInt(1, cid); cs.setInt(2, fid); cs.setString(3, cabin); cs.setString(4, pax); cs.setString(5, "WEB");
            cs.registerOutParameter(6, Types.NUMERIC); cs.registerOutParameter(7, Types.VARCHAR);
            cs.execute();
        } catch (SQLException e) { oErr = e.getErrorCode(); }
        Map<String, Object> a = new HashMap<>();
        a.put("customer_id", cid); a.put("flight_id", fid); a.put("cabin", cabin); a.put("passenger", pax);
        report(name, expected, oErr, compute.<Map<String, Object>, Map<String, Object>>execute(PKG + "BookFlightTask", a));
    }

    private void cancelError(String name, int expected, int bookingId) throws Exception {
        int oErr = 0;
        try (CallableStatement cs = ora.prepareCall("{call sp_cancel_booking(?, ?)}")) {
            cs.setInt(1, bookingId); cs.registerOutParameter(2, Types.NUMERIC);
            cs.execute();
        } catch (SQLException e) { oErr = e.getErrorCode(); }
        report(name, expected, oErr, compute.<Integer, Map<String, Object>>execute(PKG + "CancelBookingTask", bookingId));
    }

    private void flightError(String name, int expected, int fid, int minutes, boolean cancel) throws Exception {
        int oErr = 0;
        try (CallableStatement cs = ora.prepareCall(cancel ? "{call sp_cancel_flight(?, ?, ?)}" : "{call sp_record_delay(?, ?, ?, ?)}")) {
            cs.setInt(1, fid);
            if (cancel) { cs.setString(2, null); cs.registerOutParameter(3, Types.NUMERIC); }
            else { cs.setInt(2, minutes); cs.setString(3, null); cs.registerOutParameter(4, Types.NUMERIC); }
            cs.execute();
        } catch (SQLException e) { oErr = e.getErrorCode(); }
        Map<String, Object> a = new HashMap<>();
        a.put("flight_id", fid); a.put("minutes", minutes); a.put("cancel", cancel);
        report(name, expected, oErr, compute.<Map<String, Object>, Map<String, Object>>execute(PKG + "RecordDelayTask", a));
    }

    private void report(String name, int expected, int oraErr, Map<String, Object> job) {
        Object jobErr = job.get("error_no");
        check(name + ": Oracle raises " + oraErr + ", job returns " + jobErr, oraErr == expected && Integer.valueOf(expected).equals(jobErr), "expected " + expected);
    }

    // ------------------------------------------------------------------ helpers

    private void check(String what, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.println("   " + (ok ? "PASS " : "FAIL ") + what + (ok ? "" : "  -> " + detail));
    }

    private List<List<Object>> ora(String sql) throws SQLException {
        try (Statement s = ora.createStatement(); ResultSet rs = s.executeQuery(sql)) { return read(rs); }
    }

    private List<List<Object>> gg(String sql) throws SQLException {
        return ParityMariaDb.readAll(gg, sql);
    }

    /** Oracle returns oracle.sql.TIMESTAMP from getObject; read timestamp columns through getTimestamp. */
    private static List<List<Object>> read(ResultSet rs) throws SQLException {
        List<List<Object>> out = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                int t = md.getColumnType(i);
                row.add(t == Types.TIMESTAMP || t == Types.TIMESTAMP_WITH_TIMEZONE || t == -101 || t == -102 ? rs.getTimestamp(i) : rs.getObject(i));
            }
            out.add(row);
        }
        return out;
    }
}
