package poc.api.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import poc.api.model.Dto.Page;
import poc.api.model.TravelDto.Airport;
import poc.api.model.TravelDto.Booking;
import poc.api.model.TravelDto.BookingSegment;
import poc.api.model.TravelDto.Compensation;
import poc.api.model.TravelDto.Flight;
import poc.api.model.TravelDto.FlightEvent;
import poc.api.model.TravelDto.FlightInput;
import poc.api.model.TravelDto.FlightInventory;
import poc.api.model.TravelDto.InventoryInput;

/** Reads and the few direct writes on the travel tables. Bookings and cancellations go through the compute jobs, never raw SQL. */
@Repository
public class TravelRepository {
    private static final String FLIGHT_COLS = "flight_id, flight_no, origin, destination, dep_time, arr_time, distance_km, aircraft, status, delay_minutes";
    private static final String BOOKING_COLS = "booking_id, customer_id, booking_ref, status, booked_at, channel, total_fare, refunded, currency";
    private static final String SEGMENT_COLS = "segment_id, booking_id, flight_id, cabin, seat_no, passenger_name, fare, status, miles_earned";

    private final JdbcClient jdbc;
    private final Ids ids;

    TravelRepository(JdbcClient jdbc, Ids ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    // ------------------------------------------------------------------ airports

    public List<Airport> airports() {
        return jdbc.sql("SELECT iata, name, city, country, tz FROM airport ORDER BY iata").query((rs, n) -> {
            Cols c = new Cols(rs);
            return new Airport(c.string(), c.string(), c.string(), c.string(), c.string());
        }).list();
    }

    public Optional<Airport> airport(String iata) {
        return airports().stream().filter(a -> a.iata().equalsIgnoreCase(iata)).findFirst();
    }

    // ------------------------------------------------------------------ flights

    private static Flight flight(ResultSet rs, int row) throws SQLException {
        Cols c = new Cols(rs);
        return new Flight(c.integer(), c.string(), c.string(), c.string(), c.instant(), c.instant(), c.integer(), c.string(), c.string(), c.integer(), null);
    }

    public Page<Flight> flights(String origin, String destination, String status, Instant from, Instant to, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (origin != null) { where.append(" AND origin = ?"); args.add(origin.toUpperCase()); }
        if (destination != null) { where.append(" AND destination = ?"); args.add(destination.toUpperCase()); }
        if (status != null) { where.append(" AND status = ?"); args.add(status); }
        if (from != null) { where.append(" AND dep_time >= ?"); args.add(Timestamp.from(from)); }
        if (to != null) { where.append(" AND dep_time < ?"); args.add(Timestamp.from(to)); }
        long total = jdbc.sql("SELECT COUNT(*) FROM flight" + where).params(args).query(Long.class).single();
        List<Object> page = new ArrayList<>(args);
        page.add(limit);
        page.add(offset);
        List<Flight> items = jdbc.sql("SELECT " + FLIGHT_COLS + " FROM flight" + where + " ORDER BY dep_time, flight_id LIMIT ? OFFSET ?")
            .params(page).query(TravelRepository::flight).list();
        return new Page<>(items, limit, offset, total);
    }

    public Optional<Flight> flight(int id) {
        return jdbc.sql("SELECT " + FLIGHT_COLS + " FROM flight WHERE flight_id = ?").param(id).query(TravelRepository::flight).optional()
            .map(f -> new Flight(f.flightId(), f.flightNo(), f.origin(), f.destination(), f.depTime(), f.arrTime(), f.distanceKm(), f.aircraft(),
                f.status(), f.delayMinutes(), inventory(id)));
    }

    public List<FlightInventory> inventory(int flightId) {
        return jdbc.sql("SELECT cabin, seats_total, seats_available, base_fare FROM flight_inventory WHERE flight_id = ? "
                + "ORDER BY CASE cabin WHEN 'ECONOMY' THEN 1 WHEN 'BUSINESS' THEN 2 ELSE 3 END").param(flightId)
            .query((rs, n) -> {
                Cols c = new Cols(rs);
                return new FlightInventory(c.string(), c.integer(), c.integer(), c.decimal());
            }).list();
    }

    public List<FlightEvent> events(int flightId) {
        return jdbc.sql("SELECT event_id, flight_id, event_type, detail, event_time FROM flight_event WHERE flight_id = ? ORDER BY event_time, event_id")
            .param(flightId).query((rs, n) -> {
                Cols c = new Cols(rs);
                return new FlightEvent(c.integer(), c.integer(), c.string(), c.string(), c.instant());
            }).list();
    }

    public long segmentCount(int flightId) {
        return jdbc.sql("SELECT COUNT(*) FROM booking_segment WHERE flight_id = ?").param(flightId).query(Long.class).single();
    }

    public Flight createFlight(FlightInput in) {
        int id = ids.next("flight");
        jdbc.sql("INSERT INTO flight (" + FLIGHT_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', 0)")
            .params(id, in.flightNo(), in.origin().toUpperCase(), in.destination().toUpperCase(), Cols.ts(in.depTime()), Cols.ts(in.arrTime()),
                in.distanceKm(), in.aircraft()).update();
        for (InventoryInput i : in.inventory()) {
            jdbc.sql("INSERT INTO flight_inventory (flight_id, cabin, seats_total, seats_available, next_seat, base_fare) VALUES (?, ?, ?, ?, 1, ?)")
                .params(id, i.cabin(), i.seats(), i.seats(), i.baseFare()).update();
        }
        return flight(id).orElseThrow();
    }

    public boolean updateFlight(int id, FlightInput in) {
        return jdbc.sql("UPDATE flight SET flight_no = ?, origin = ?, destination = ?, dep_time = ?, arr_time = ?, distance_km = ?, aircraft = ? WHERE flight_id = ?")
            .params(in.flightNo(), in.origin().toUpperCase(), in.destination().toUpperCase(), Cols.ts(in.depTime()), Cols.ts(in.arrTime()),
                in.distanceKm(), in.aircraft(), id).update() > 0;
    }

    public boolean deleteFlight(int id) {
        jdbc.sql("DELETE FROM flight_event WHERE flight_id = ?").param(id).update();
        jdbc.sql("DELETE FROM flight_inventory WHERE flight_id = ?").param(id).update();
        return jdbc.sql("DELETE FROM flight WHERE flight_id = ?").param(id).update() > 0;
    }

    // ------------------------------------------------------------------ bookings (read)

    private static Booking booking(ResultSet rs, int row) throws SQLException {
        Cols c = new Cols(rs);
        return new Booking(c.integer(), c.integer(), c.string(), c.string(), c.instant(), c.string(), c.decimal(), c.decimal(), c.string(), null);
    }

    public Page<Booking> bookings(Integer customerId, String status, Integer flightId, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (customerId != null) { where.append(" AND customer_id = ?"); args.add(customerId); }
        if (status != null) { where.append(" AND status = ?"); args.add(status); }
        if (flightId != null) { where.append(" AND booking_id IN (SELECT booking_id FROM booking_segment WHERE flight_id = ?)"); args.add(flightId); }
        long total = jdbc.sql("SELECT COUNT(*) FROM booking" + where).params(args).query(Long.class).single();
        List<Object> page = new ArrayList<>(args);
        page.add(limit);
        page.add(offset);
        List<Booking> items = jdbc.sql("SELECT " + BOOKING_COLS + " FROM booking" + where + " ORDER BY booked_at DESC, booking_id DESC LIMIT ? OFFSET ?")
            .params(page).query(TravelRepository::booking).list();
        return new Page<>(items, limit, offset, total);
    }

    public Optional<Booking> booking(int id) {
        return jdbc.sql("SELECT " + BOOKING_COLS + " FROM booking WHERE booking_id = ?").param(id).query(TravelRepository::booking).optional()
            .map(b -> new Booking(b.bookingId(), b.customerId(), b.bookingRef(), b.status(), b.bookedAt(), b.channel(), b.totalFare(), b.refunded(),
                b.currency(), segments(id, b.customerId())));
    }

    private List<BookingSegment> segments(int bookingId, int customerId) {
        return jdbc.sql("SELECT " + SEGMENT_COLS + " FROM booking_segment WHERE customer_id = ? AND booking_id = ? ORDER BY segment_id")
            .params(customerId, bookingId).query((rs, n) -> {
                Cols c = new Cols(rs);
                return new BookingSegment(c.integer(), c.integer(), c.integer(), c.string(), c.string(), c.string(), c.decimal(), c.string(), c.integer());
            }).list();
    }

    // ------------------------------------------------------------------ compensation (read)

    public Page<Compensation> compensation(Integer customerId, Integer flightId, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (customerId != null) { where.append(" AND c.customer_id = ?"); args.add(customerId); }
        if (flightId != null) { where.append(" AND s.flight_id = ?"); args.add(flightId); }
        String from = " FROM compensation c JOIN booking_segment s ON s.segment_id = c.segment_id AND s.customer_id = c.customer_id" + where;
        long total = jdbc.sql("SELECT COUNT(*)" + from).params(args).query(Long.class).single();
        List<Object> page = new ArrayList<>(args);
        page.add(limit);
        page.add(offset);
        List<Compensation> items = jdbc.sql("SELECT c.segment_id, c.customer_id, c.amount, c.reason, c.created_at" + from
                + " ORDER BY c.segment_id LIMIT ? OFFSET ?").params(page).query((rs, n) -> {
                Cols c = new Cols(rs);
                return new Compensation(c.integer(), c.integer(), c.decimal(), c.string(), c.instant());
            }).list();
        return new Page<>(items, limit, offset, total);
    }
}
