package poc.api.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.IgniteException;
import org.apache.ignite.client.ClientException;
import org.apache.ignite.client.IgniteClient;
import org.springframework.stereotype.Service;
import poc.api.model.Dto.CategoryCount;
import poc.api.model.Dto.OfferRun;
import poc.api.model.Dto.PlaceOrderRequest;
import poc.api.model.Dto.PlaceOrderResult;
import poc.api.model.TravelDto.BookFlightRequest;
import poc.api.model.TravelDto.BookFlightResult;
import poc.api.model.TravelDto.CancelBookingResult;
import poc.api.model.TravelDto.CancelFlightRequest;
import poc.api.model.TravelDto.DelayRequest;
import poc.api.model.TravelDto.FlightOpResult;
import poc.api.web.ApiExceptions.BadRequest;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;
import poc.api.web.ApiExceptions.Unavailable;

/**
 * Calls the GridGain compute jobs (poc.compute.jobs.*, deployed on every node) that replaced the Sybase
 * stored procedures. Jobs report business errors as {rc, error_no, error}, using the same numbers the
 * stored procedures raised (20002 not found, 20003 insufficient stock, ...); those are mapped to HTTP here.
 */
@Service
public class ComputeService {
    private static final String PKG = "poc.compute.jobs.";

    private final IgniteClient client;

    ComputeService(IgniteClient client) {
        this.client = client;
    }

    @SuppressWarnings("unchecked")
    private <R> R run(String task, Object arg) {
        try {
            return (R) client.compute().withTimeout(60_000).execute(PKG + task, arg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while waiting for GridGain compute", e);
        } catch (ClientException | IgniteException e) {
            throw new Unavailable("GridGain compute call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Maps a job's business error to HTTP. Sybase codes (20002 customer not found, 20003 insufficient stock, 2000x bad input) and
     * Oracle codes (20103 flight / 20110 booking not found; 20106 flight closed, 20107 sold out, 20111 booking not active,
     * 20112 nothing to cancel, 20121 flight already operated = conflicts; 20104, 20105, 20120 bad input).
     */
    private static Map<String, Object> checked(Map<String, Object> result, String what, Object id) {
        if (((Number) result.get("rc")).intValue() == 0) return result;
        String message = String.valueOf(result.get("error"));
        switch (((Number) result.get("error_no")).intValue()) {
            case 20002: case 20103: case 20110: throw new NotFound(what, id);
            case 20003: case 20106: case 20107: case 20111: case 20112: case 20121: throw new Conflict(message);
            default: throw new BadRequest(message);
        }
    }

    /** Replaces sp_customer_360: profile, order summary, recent orders, top categories, interactions, loyalty. */
    public Map<String, Object> customer360(int customerId) {
        Map<String, Object> r = run("Customer360Task", customerId);
        checked(r, "Customer", customerId);
        r.remove("rc");
        return r;
    }

    /** Replaces sp_recalc_loyalty_tier. */
    public Map<String, Object> recalculateTier(int customerId) {
        Map<String, Object> r = run("RecalcLoyaltyTierTask", customerId);
        checked(r, "Customer", customerId);
        r.remove("rc");
        return r;
    }

    /** Replaces sp_score_customers: one job per node scores the customers that node owns. */
    public List<Object> scoreCustomers(Long asOfMillis) {
        return run("ScoreCustomersTask", asOfMillis);
    }

    /** Replaces sp_create_order + sp_add_order_item + sp_complete_order, as one transaction. */
    public PlaceOrderResult placeOrder(PlaceOrderRequest req) {
        List<Object> flat = new ArrayList<>();
        req.items().forEach(l -> {
            flat.add(l.productId());
            flat.add(l.quantity());
        });
        Map<String, Object> arg = new HashMap<>();
        arg.put("customer_id", req.customerId());
        arg.put("channel", req.channel() == null ? "WEB" : req.channel());
        arg.put("payment_method", req.paymentMethod() == null ? "CARD" : req.paymentMethod());
        arg.put("items", flat);

        Map<String, Object> r = run("PlaceOrderTask", arg);
        checked(r, "Customer", req.customerId());
        return new PlaceOrderResult((Integer) r.get("order_id"), (Integer) r.get("customer_id"),
            (BigDecimal) r.get("total_amount"), (Integer) r.get("points"), (String) r.get("tier_before"),
            (String) r.get("tier_after"), (Integer) r.get("loyalty_points"));
    }

    // ------------------------------------------------------------------ travel (Oracle) jobs

    /** Replaces sp_customer_trips: upcoming trips, past trips and statistics. */
    public Map<String, Object> trips(int customerId) {
        Map<String, Object> r = run("TripsTask", customerId);
        r.remove("rc");
        return r;
    }

    /** Replaces sp_book_flight: seat inventory, booking and segment in one transaction. */
    public BookFlightResult bookFlight(BookFlightRequest req) {
        Map<String, Object> arg = new HashMap<>();
        arg.put("customer_id", req.customerId());
        arg.put("flight_id", req.flightId());
        arg.put("cabin", req.cabin());
        arg.put("passenger", req.passenger());
        arg.put("channel", req.channel() == null ? "WEB" : req.channel());
        Map<String, Object> r = checked(run("BookFlightTask", arg), "Flight", req.flightId());
        return new BookFlightResult((Integer) r.get("booking_id"), (String) r.get("booking_ref"), (Integer) r.get("customer_id"),
            (Integer) r.get("flight_id"), (String) r.get("cabin"), (String) r.get("seat_no"), (BigDecimal) r.get("fare"), (Integer) r.get("miles_earned"));
    }

    /** Replaces sp_cancel_booking: cancels future segments, releases seats, returns the refund. */
    public CancelBookingResult cancelBooking(int bookingId) {
        Map<String, Object> r = checked(run("CancelBookingTask", bookingId), "Booking", bookingId);
        return new CancelBookingResult((Integer) r.get("booking_id"), (BigDecimal) r.get("refund"), (Integer) r.get("segments_cancelled"),
            (String) r.get("booking_status"));
    }

    /** Replaces sp_record_delay. */
    public FlightOpResult delayFlight(int flightId, DelayRequest req) {
        Map<String, Object> arg = new HashMap<>();
        arg.put("flight_id", flightId);
        arg.put("minutes", req.minutes());
        arg.put("reason", req.reason());
        arg.put("cancel", false);
        return flightOp(checked(run("RecordDelayTask", arg), "Flight", flightId));
    }

    /** Replaces sp_cancel_flight. */
    public FlightOpResult cancelFlight(int flightId, CancelFlightRequest req) {
        Map<String, Object> arg = new HashMap<>();
        arg.put("flight_id", flightId);
        arg.put("reason", req == null ? null : req.reason());
        arg.put("cancel", true);
        return flightOp(checked(run("RecordDelayTask", arg), "Flight", flightId));
    }

    private static FlightOpResult flightOp(Map<String, Object> r) {
        return new FlightOpResult((Integer) r.get("flight_id"), (String) r.get("status"), ((Number) r.get("affected_bookings")).longValue());
    }

    /** Replaces sp_process_disruptions: one job per node computes compensation for the segments it owns. */
    public List<Object> processDisruptions(int minDelay, Long asOfMillis) {
        Map<String, Object> arg = new HashMap<>();
        arg.put("min_delay", minDelay);
        if (asOfMillis != null) arg.put("as_of", asOfMillis);
        return run("ProcessDisruptionsTask", arg);
    }

    // ------------------------------------------------------------------ hub jobs (join Oracle travel data with Sybase CRM data)

    /** Customer view joining both systems: profile, loyalty and retail spend (Sybase) with bookings, miles and trips (Oracle). */
    public Map<String, Object> customerHub(int customerId) {
        Map<String, Object> r = checked(run("CustomerHubTask", customerId), "Customer", customerId);
        r.remove("rc");
        return r;
    }

    /** Passengers on a flight ranked by customer value, with tier-weighted compensation (Oracle passengers x Sybase loyalty and orders). */
    public Map<String, Object> disruptionImpact(int flightId) {
        Map<String, Object> r = run("DisruptionImpactTask", flightId);
        r.remove("rc");
        return r;
    }

    // ------------------------------------------------------------------ offers

    /** Runs the recommender on every node (or just the node owning one customer) and writes the offers to customer_offer. */
    @SuppressWarnings("unchecked")
    public OfferRun generateOffers(int topN, Integer customerId) {
        Map<String, Object> arg = new HashMap<>();
        arg.put("top_n", topN);
        if (customerId != null) arg.put("customer_id", customerId);
        Map<String, Object> r = checked(run("RecommendOffersTask", arg), "Customer", customerId);
        List<CategoryCount> cats = new ArrayList<>();
        for (Object o : (List<Object>) r.get("by_category")) {
            Map<String, Object> m = (Map<String, Object>) o;
            cats.add(new CategoryCount((String) m.get("category"), ((Number) m.get("offers")).longValue()));
        }
        return new OfferRun(((Number) r.get("customers")).longValue(), ((Number) r.get("customers_with_offers")).longValue(),
            ((Number) r.get("offers")).longValue(), ((Number) r.get("avg_discount_pct")).doubleValue(), ((Number) r.get("avg_score")).doubleValue(), cats);
    }
}
