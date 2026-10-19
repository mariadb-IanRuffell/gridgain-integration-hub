package poc.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.IgniteException;
import org.apache.ignite.client.ClientCompute;
import org.apache.ignite.client.ClientException;
import org.apache.ignite.client.IgniteClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import poc.api.model.Dto.*;
import poc.api.model.TravelDto.*;
import poc.api.web.ApiExceptions.*;

class ComputeServiceTest {
    IgniteClient client = mock(IgniteClient.class);
    ClientCompute compute = mock(ClientCompute.class);
    ComputeService service = new ComputeService(client);

    @BeforeEach
    void wire() {
        when(client.compute()).thenReturn(compute);
        when(compute.withTimeout(anyLong())).thenReturn(compute);
    }

    private void jobReturns(String task, Object result) throws Exception {
        doReturn(result).when(compute).execute(eq("poc.compute.jobs." + task), any());
    }

    private static Map<String, Object> ok(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rc", 0);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> fail(int errorNo, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rc", -1);
        m.put("error_no", errorNo);
        m.put("error", message);
        return m;
    }

    @Test
    void customer360StripsTheReturnCode() throws Exception {
        jobReturns("Customer360Task", ok("profile", List.of(Map.of("customer_id", 7))));
        Map<String, Object> r = service.customer360(7);
        assertFalse(r.containsKey("rc"));
        assertTrue(r.containsKey("profile"));
        verify(compute).withTimeout(60_000);
        verify(compute).execute("poc.compute.jobs.Customer360Task", 7);
    }

    @Test
    void jobErrorNumbersMapToHttpExceptions() throws Exception {
        for (int notFound : new int[] {20002, 20103, 20110}) {
            jobReturns("Customer360Task", fail(notFound, "x"));
            NotFound e = assertThrows(NotFound.class, () -> service.customer360(7));
            assertEquals("Customer 7 not found", e.getMessage());
        }
        for (int conflict : new int[] {20003, 20106, 20107, 20111, 20112, 20121}) {
            jobReturns("Customer360Task", fail(conflict, "business rule " + conflict));
            Conflict e = assertThrows(Conflict.class, () -> service.customer360(7));
            assertEquals("business rule " + conflict, e.getMessage());
        }
        for (int bad : new int[] {20001, 20104, 20105, 20120, 12345}) {
            jobReturns("Customer360Task", fail(bad, "bad input"));
            assertThrows(BadRequest.class, () -> service.customer360(7));
        }
    }

    @Test
    void recalculateTier() throws Exception {
        jobReturns("RecalcLoyaltyTierTask", ok("tier_after", "GOLD"));
        assertEquals(Map.of("tier_after", "GOLD"), service.recalculateTier(7));
    }

    @Test
    void scoreCustomersPassesAsOfThrough() throws Exception {
        jobReturns("ScoreCustomersTask", List.of());
        assertEquals(List.of(), service.scoreCustomers(5L));
        verify(compute).execute("poc.compute.jobs.ScoreCustomersTask", 5L);
        service.scoreCustomers(null);
        verify(compute).execute("poc.compute.jobs.ScoreCustomersTask", (Object) null);
    }

    @Test
    void placeOrderFlattensLinesAndAppliesDefaults() throws Exception {
        Map<String, Object> r = ok("order_id", 1000000005, "customer_id", 7, "total_amount", new BigDecimal("1560.00"), "points", 1560,
            "tier_before", "GOLD", "tier_after", "PLATINUM", "loyalty_points", 61000);
        jobReturns("PlaceOrderTask", r);
        PlaceOrderResult out = service.placeOrder(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(1, 2), new OrderLine(13, 1))));
        assertEquals(1000000005, out.orderId());
        assertEquals("PLATINUM", out.tierAfter());
        assertEquals(new BigDecimal("1560.00"), out.totalAmount());

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> arg = ArgumentCaptor.forClass(Map.class);
        verify(compute).execute(eq("poc.compute.jobs.PlaceOrderTask"), arg.capture());
        assertEquals(Map.of("customer_id", 7, "channel", "WEB", "payment_method", "CARD", "items", List.of(1, 2, 13, 1)), arg.getValue());
    }

    @Test
    void placeOrderKeepsExplicitChannelAndMethod() throws Exception {
        jobReturns("PlaceOrderTask", ok("order_id", 1, "customer_id", 7, "total_amount", BigDecimal.ONE, "points", 1, "tier_before", "BLUE",
            "tier_after", "BLUE", "loyalty_points", 1));
        service.placeOrder(new PlaceOrderRequest(7, "APP", "POINTS", List.of(new OrderLine(1, 1))));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> arg = ArgumentCaptor.forClass(Map.class);
        verify(compute).execute(eq("poc.compute.jobs.PlaceOrderTask"), arg.capture());
        assertEquals("APP", arg.getValue().get("channel"));
        assertEquals("POINTS", arg.getValue().get("payment_method"));
    }

    @Test
    void placeOrderErrors() throws Exception {
        jobReturns("PlaceOrderTask", fail(20003, "Insufficient stock for product 1"));
        assertThrows(Conflict.class, () -> service.placeOrder(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(1, 1)))));
        jobReturns("PlaceOrderTask", fail(20002, "no customer"));
        assertThrows(NotFound.class, () -> service.placeOrder(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(1, 1)))));
    }

    @Test
    void bookFlightMapsTheResultAndDefaultsTheChannel() throws Exception {
        jobReturns("BookFlightTask", ok("booking_id", 1000000007, "booking_ref", "VNLMYO", "customer_id", 222, "flight_id", 143, "cabin", "BUSINESS",
            "seat_no", "3A", "fare", new BigDecimal("4552.50"), "miles_earned", 8205));
        BookFlightResult r = service.bookFlight(new BookFlightRequest(222, 143, "BUSINESS", "Ava Hassan", null));
        assertEquals("VNLMYO", r.bookingRef());
        assertEquals("3A", r.seatNo());
        assertEquals(8205, r.milesEarned());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> arg = ArgumentCaptor.forClass(Map.class);
        verify(compute).execute(eq("poc.compute.jobs.BookFlightTask"), arg.capture());
        assertEquals("WEB", arg.getValue().get("channel"));
        assertEquals("Ava Hassan", arg.getValue().get("passenger"));
    }

    @Test
    void bookFlightErrorsNameTheFlight() throws Exception {
        jobReturns("BookFlightTask", fail(20103, "no flight"));
        assertEquals("Flight 143 not found", assertThrows(NotFound.class,
            () -> service.bookFlight(new BookFlightRequest(1, 143, "FIRST", "A", null))).getMessage());
        jobReturns("BookFlightTask", fail(20107, "Cabin sold out"));
        assertThrows(Conflict.class, () -> service.bookFlight(new BookFlightRequest(1, 143, "FIRST", "A", null)));
    }

    @Test
    void cancelBooking() throws Exception {
        jobReturns("CancelBookingTask", ok("booking_id", 5, "refund", new BigDecimal("900.00"), "segments_cancelled", 2, "booking_status", "CANCELLED"));
        CancelBookingResult r = service.cancelBooking(5);
        assertEquals(new BigDecimal("900.00"), r.refund());
        assertEquals(2, r.segmentsCancelled());
        jobReturns("CancelBookingTask", fail(20110, "no booking"));
        assertEquals("Booking 5 not found", assertThrows(NotFound.class, () -> service.cancelBooking(5)).getMessage());
        jobReturns("CancelBookingTask", fail(20111, "already cancelled"));
        assertThrows(Conflict.class, () -> service.cancelBooking(5));
    }

    @Test
    void delayAndCancelFlight() throws Exception {
        jobReturns("RecordDelayTask", ok("flight_id", 134, "status", "DELAYED", "affected_bookings", 8));
        FlightOpResult r = service.delayFlight(134, new DelayRequest(300, "weather"));
        assertEquals(new FlightOpResult(134, "DELAYED", 8L), r);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> arg = ArgumentCaptor.forClass(Map.class);
        verify(compute).execute(eq("poc.compute.jobs.RecordDelayTask"), arg.capture());
        assertEquals(false, arg.getValue().get("cancel"));
        assertEquals(300, arg.getValue().get("minutes"));

        jobReturns("RecordDelayTask", ok("flight_id", 134, "status", "CANCELLED", "affected_bookings", 5));
        assertEquals("CANCELLED", service.cancelFlight(134, null).status());
        assertEquals("CANCELLED", service.cancelFlight(134, new CancelFlightRequest("crew")).status());

        jobReturns("RecordDelayTask", fail(20121, "already operated"));
        assertThrows(Conflict.class, () -> service.delayFlight(134, new DelayRequest(10, null)));
    }

    @Test
    void hubAndTripsAndImpactStripTheReturnCode() throws Exception {
        jobReturns("CustomerHubTask", ok("value", List.of()));
        assertFalse(service.customerHub(72).containsKey("rc"));
        jobReturns("CustomerHubTask", fail(20002, "x"));
        assertThrows(NotFound.class, () -> service.customerHub(72));
        jobReturns("TripsTask", ok("upcoming", List.of()));
        assertFalse(service.trips(72).containsKey("rc"));
        jobReturns("DisruptionImpactTask", ok("passengers", List.of()));
        assertFalse(service.disruptionImpact(134).containsKey("rc"));
    }

    @Test
    void processDisruptionsOmitsAsOfWhenNotGiven() throws Exception {
        jobReturns("ProcessDisruptionsTask", List.of());
        service.processDisruptions(180, null);
        service.processDisruptions(360, 99L);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> arg = ArgumentCaptor.forClass(Map.class);
        verify(compute, times(2)).execute(eq("poc.compute.jobs.ProcessDisruptionsTask"), arg.capture());
        assertEquals(Map.of("min_delay", 180), arg.getAllValues().get(0));
        assertEquals(Map.of("min_delay", 360, "as_of", 99L), arg.getAllValues().get(1));
    }

    @Test
    void clusterFailuresBecomeUnavailable() throws Exception {
        doThrow(new ClientException("connection lost")).when(compute).execute(anyString(), any());
        assertTrue(assertThrows(Unavailable.class, () -> service.customer360(7)).getMessage().contains("connection lost"));
        doThrow(new IgniteException("task failed")).when(compute).execute(anyString(), any());
        assertThrows(Unavailable.class, () -> service.scoreCustomers(null));
    }

    @Test
    void interruptionIsReportedAndTheFlagRestored() throws Exception {
        doThrow(new InterruptedException()).when(compute).execute(anyString(), any());
        try {
            assertThrows(Unavailable.class, () -> service.customer360(7));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();   // do not leak the flag into other tests
        }
    }

    @Test
    void generateOffersBuildsTheArgumentAndMapsTheSummary() throws Exception {
        jobReturns("RecommendOffersTask", ok("customers", 240L, "customers_with_offers", 240L, "offers", 720L, "avg_discount_pct", 5.03, "avg_score", 1.1273,
            "by_category", List.of(Map.of("category", "FLIGHT", "offers", 360L), Map.of("category", "HOTEL", "offers", 34L))));
        OfferRun r = service.generateOffers(3, null);
        assertEquals(720L, r.offers());
        assertEquals(240L, r.customersWithOffers());
        assertEquals(5.03, r.avgDiscountPct());
        assertEquals(List.of(new CategoryCount("FLIGHT", 360L), new CategoryCount("HOTEL", 34L)), r.byCategory());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> arg = ArgumentCaptor.forClass(Map.class);
        verify(compute).execute(eq("poc.compute.jobs.RecommendOffersTask"), arg.capture());
        assertEquals(Map.of("top_n", 3), arg.getValue());
        service.generateOffers(5, 72);
        verify(compute, times(2)).execute(eq("poc.compute.jobs.RecommendOffersTask"), arg.capture());
        assertEquals(Map.of("top_n", 5, "customer_id", 72), arg.getValue());
    }

    @Test
    void generateOffersForAnUnknownCustomerIsNotFound() throws Exception {
        jobReturns("RecommendOffersTask", fail(20002, "Customer not found or not active"));
        assertEquals("Customer 999 not found", assertThrows(NotFound.class, () -> service.generateOffers(3, 999)).getMessage());
    }
}
