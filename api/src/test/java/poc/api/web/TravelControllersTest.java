package poc.api.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import poc.api.model.Dto.Page;
import poc.api.model.TravelDto.*;
import poc.api.repo.CustomerRepository;
import poc.api.repo.TravelRepository;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;

@WebMvcTest
@Import({FlightController.class, BookingController.class, TravelHubController.class, AirportController.class, ApiExceptionHandler.class})
class TravelControllersTest {
    @Autowired MockMvc mvc;
    @MockBean TravelRepository travel;
    @MockBean ComputeService compute;
    @MockBean CustomerRepository customers;

    static final Instant DEP = Instant.parse("2026-11-01T08:00:00Z");
    static final Flight FLIGHT = new Flight(134, "XA1103", "DXB", "LHR", DEP, DEP.plusSeconds(25_000), 5470, "A380", "SCHEDULED", 0, List.of());

    static String flightBody(String origin, String destination, String dep, String arr, String inventory) {
        return "{\"flightNo\":\"XA9001\",\"origin\":\"" + origin + "\",\"destination\":\"" + destination + "\",\"depTime\":\"" + dep
            + "\",\"arrTime\":\"" + arr + "\",\"distanceKm\":5470" + (inventory == null ? "" : ",\"inventory\":" + inventory) + "}";
    }

    static final String INV = "[{\"cabin\":\"ECONOMY\",\"seats\":300,\"baseFare\":1500}]";

    @BeforeEach
    void airports() {
        when(travel.airport(anyString())).thenReturn(Optional.empty());
        when(travel.airport("DXB")).thenReturn(Optional.of(new Airport("DXB", "Dubai", "Dubai", "AE", "Asia/Dubai")));
        when(travel.airport("LHR")).thenReturn(Optional.of(new Airport("LHR", "Heathrow", "London", "GB", "Europe/London")));
        when(travel.airport("dxb")).thenReturn(Optional.of(new Airport("DXB", "Dubai", "Dubai", "AE", "Asia/Dubai")));
    }

    // ---- airports

    @Test
    void airportsListAndGet() throws Exception {
        when(travel.airports()).thenReturn(List.of(new Airport("DXB", "Dubai", "Dubai", "AE", "Asia/Dubai")));
        mvc.perform(get("/api/v1/airports")).andExpect(status().isOk()).andExpect(jsonPath("$[0].iata").value("DXB"));
        mvc.perform(get("/api/v1/airports/DXB")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/airports/XXX")).andExpect(status().isNotFound());
    }

    // ---- flights

    @Test
    void flightListParsesDatesAndTrimsCodes() throws Exception {
        when(travel.flights(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(FLIGHT), 50, 0, 1));
        mvc.perform(get("/api/v1/flights?destination=&status=DELAYED&from=2026-11-01T00:00:00Z&to=2026-12-01T00:00:00Z").param("origin", " DXB"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].flightNo").value("XA1103"));
        verify(travel).flights("DXB", null, "DELAYED", Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), 50, 0);
        mvc.perform(get("/api/v1/flights?status=LANDED")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/flights?from=yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    void flightGetAndEvents() throws Exception {
        when(travel.flight(134)).thenReturn(Optional.of(FLIGHT));
        when(travel.events(134)).thenReturn(List.of(new FlightEvent(1, 134, "DELAY", "Delayed 300 min", DEP)));
        mvc.perform(get("/api/v1/flights/134")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/flights/135")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/flights/134/events")).andExpect(status().isOk()).andExpect(jsonPath("$[0].eventType").value("DELAY"));
        mvc.perform(get("/api/v1/flights/135/events")).andExpect(status().isNotFound());
    }

    @Test
    void createFlightValidatesBusinessRules() throws Exception {
        when(travel.createFlight(any())).thenReturn(FLIGHT);
        String ok = flightBody("DXB", "LHR", "2026-11-01T08:00:00Z", "2026-11-01T15:00:00Z", INV);
        mvc.perform(post("/api/v1/flights").contentType(MediaType.APPLICATION_JSON).content(ok))
            .andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/flights/134")));

        record Case(String body, String detail) {}
        for (Case c : List.of(
                new Case(flightBody("DXB", "LHR", "2026-11-01T15:00:00Z", "2026-11-01T08:00:00Z", INV), "arrTime must be after depTime"),
                new Case(flightBody("DXB", "dxb", "2026-11-01T08:00:00Z", "2026-11-01T15:00:00Z", INV), "origin and destination must differ"),
                new Case(flightBody("DXB", "ZZZ", "2026-11-01T08:00:00Z", "2026-11-01T15:00:00Z", INV), "Unknown airport ZZZ"),
                new Case(flightBody("DXB", "LHR", "2026-11-01T08:00:00Z", "2026-11-01T15:00:00Z", null), "inventory is required when creating a flight"),
                new Case(flightBody("DXB", "LHR", "2026-11-01T08:00:00Z", "2026-11-01T15:00:00Z", "[]"), "inventory is required when creating a flight"),
                new Case(flightBody("DXB", "LHR", "2026-11-01T08:00:00Z", "2026-11-01T15:00:00Z",
                    "[{\"cabin\":\"FIRST\",\"seats\":8,\"baseFare\":9000},{\"cabin\":\"FIRST\",\"seats\":8,\"baseFare\":9000}]"), "inventory lists a cabin more than once"))) {
            mvc.perform(post("/api/v1/flights").contentType(MediaType.APPLICATION_JSON).content(c.body()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(c.detail()));
        }
        verify(travel, times(1)).createFlight(any());
    }

    @Test
    void flightBodyConstraints() throws Exception {
        mvc.perform(post("/api/v1/flights").contentType(MediaType.APPLICATION_JSON)
                .content(flightBody("DX", "LHR", "2026-11-01T08:00:00Z", "2026-11-01T15:00:00Z",
                    "[{\"cabin\":\"PREMIUM\",\"seats\":0,\"baseFare\":-1}]")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("origin")));
    }

    @Test
    void onlyScheduledOrDelayedFlightsCanBeEdited() throws Exception {
        String body = flightBody("DXB", "LHR", "2026-11-01T08:00:00Z", "2026-11-01T15:00:00Z", null);
        when(travel.flight(134)).thenReturn(Optional.of(FLIGHT));
        mvc.perform(put("/api/v1/flights/134").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(travel).updateFlight(eq(134), any());
        when(travel.flight(150)).thenReturn(Optional.of(new Flight(150, "XA1", "DXB", "LHR", DEP, DEP, 1, "A380", "DEPARTED", 0, List.of())));
        mvc.perform(put("/api/v1/flights/150").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("Flight 150 is DEPARTED and can no longer be edited"));
        mvc.perform(put("/api/v1/flights/151").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
    }

    @Test
    void flightsWithBookingsCannotBeDeleted() throws Exception {
        when(travel.flight(134)).thenReturn(Optional.of(FLIGHT));
        when(travel.segmentCount(134)).thenReturn(8L);
        mvc.perform(delete("/api/v1/flights/134")).andExpect(status().isConflict());
        when(travel.segmentCount(134)).thenReturn(0L);
        mvc.perform(delete("/api/v1/flights/134")).andExpect(status().isNoContent());
        verify(travel).deleteFlight(134);
    }

    @Test
    void delayValidatesMinutesAndDelegatesToTheJob() throws Exception {
        when(compute.delayFlight(eq(134), any())).thenReturn(new FlightOpResult(134, "DELAYED", 8L));
        mvc.perform(post("/api/v1/flights/134/delay").contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":300,\"reason\":\"weather\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.affectedBookings").value(8)).andExpect(jsonPath("$.status").value("DELAYED"));
        mvc.perform(post("/api/v1/flights/134/delay").contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/flights/134/delay").contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":1441}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/flights/134/delay").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void cancelFlightBodyIsOptional() throws Exception {
        when(compute.cancelFlight(eq(134), any())).thenReturn(new FlightOpResult(134, "CANCELLED", 8L));
        mvc.perform(post("/api/v1/flights/134/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(compute).cancelFlight(134, null);
        doThrow(new Conflict("Flight already operated")).when(compute).cancelFlight(eq(134), argThat(r -> r != null));
        mvc.perform(post("/api/v1/flights/134/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"crew\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void impactRequiresTheFlight() throws Exception {
        when(travel.flight(134)).thenReturn(Optional.of(FLIGHT));
        when(compute.disruptionImpact(134)).thenReturn(Map.of("total_suggested_compensation", new BigDecimal("100.00")));
        mvc.perform(get("/api/v1/flights/134/impact")).andExpect(status().isOk()).andExpect(jsonPath("$.total_suggested_compensation").value(100.00));
        mvc.perform(get("/api/v1/flights/999/impact")).andExpect(status().isNotFound());
        verify(compute, never()).disruptionImpact(999);
    }

    // ---- bookings

    @Test
    void bookingListFilters() throws Exception {
        when(travel.bookings(any(), any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 50, 0, 0));
        mvc.perform(get("/api/v1/bookings?customerId=72&status=CONFIRMED&flightId=134")).andExpect(status().isOk());
        verify(travel).bookings(72, "CONFIRMED", 134, 50, 0);
        mvc.perform(get("/api/v1/bookings?status=PENDING")).andExpect(status().isBadRequest());
    }

    @Test
    void bookingGetOr404() throws Exception {
        when(travel.booking(5)).thenReturn(Optional.of(new Booking(5, 72, "ABCDEF", "CONFIRMED", DEP, "WEB", BigDecimal.TEN, BigDecimal.ZERO, "AED", List.of())));
        mvc.perform(get("/api/v1/bookings/5")).andExpect(status().isOk()).andExpect(jsonPath("$.bookingRef").value("ABCDEF"));
        mvc.perform(get("/api/v1/bookings/6")).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Booking 6 not found"));
    }

    @Test
    void bookFlightCreatesTheBooking() throws Exception {
        when(compute.bookFlight(any())).thenReturn(new BookFlightResult(1000000007, "VNLMYO", 222, 143, "BUSINESS", "3A", new BigDecimal("4552.50"), 8205));
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":222,\"flightId\":143,\"cabin\":\"BUSINESS\",\"passenger\":\"Ava Hassan\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/bookings/1000000007")))
            .andExpect(jsonPath("$.seatNo").value("3A"));
    }

    @Test
    void bookFlightRejectsBadInputBeforeCallingTheJob() throws Exception {
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":222,\"flightId\":143,\"cabin\":\"PREMIUM\",\"passenger\":\"Ava Hassan\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cabin")));
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\":222,\"flightId\":143,\"cabin\":\"FIRST\",\"passenger\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        verify(compute, never()).bookFlight(any());
    }

    @Test
    void bookingConflictsAndMissingFlightsComeFromTheJob() throws Exception {
        doThrow(new Conflict("Cabin sold out"), new NotFound("Flight", 143)).when(compute).bookFlight(any());
        String body = "{\"customerId\":222,\"flightId\":143,\"cabin\":\"FIRST\",\"passenger\":\"Ava Hassan\"}";
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
    }

    @Test
    void cancelBooking() throws Exception {
        when(compute.cancelBooking(5)).thenReturn(new CancelBookingResult(5, new BigDecimal("900.00"), 2, "CANCELLED"));
        mvc.perform(post("/api/v1/bookings/5/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.refund").value(900.00));
        doThrow(new Conflict("Booking 6 is already cancelled")).when(compute).cancelBooking(6);
        mvc.perform(post("/api/v1/bookings/6/cancel")).andExpect(status().isConflict());
    }

    // ---- hub

    @Test
    void tripsRequireAKnownCustomer() throws Exception {
        when(customers.find(72)).thenReturn(Optional.of(CustomerControllerTest.customer(72)));
        when(compute.trips(72)).thenReturn(Map.of("upcoming", List.of()));
        mvc.perform(get("/api/v1/customers/72/trips")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/73/trips")).andExpect(status().isNotFound());
    }

    @Test
    void hubViewComesFromTheJob() throws Exception {
        when(compute.customerHub(72)).thenReturn(Map.of("value", List.of(Map.of("lifetime_value", 66740))));
        mvc.perform(get("/api/v1/customers/72/hub")).andExpect(status().isOk()).andExpect(jsonPath("$.value[0].lifetime_value").value(66740));
        doThrow(new NotFound("Customer", 73)).when(compute).customerHub(73);
        mvc.perform(get("/api/v1/customers/73/hub")).andExpect(status().isNotFound());
    }

    @Test
    void processDisruptionsBounds() throws Exception {
        when(compute.processDisruptions(anyInt(), any())).thenReturn(List.of());
        mvc.perform(post("/api/v1/disruptions/process")).andExpect(status().isOk());
        verify(compute).processDisruptions(180, null);
        mvc.perform(post("/api/v1/disruptions/process?minDelay=360&asOf=2026-10-15T12:00:00Z")).andExpect(status().isOk());
        verify(compute).processDisruptions(360, Instant.parse("2026-10-15T12:00:00Z").toEpochMilli());
        mvc.perform(post("/api/v1/disruptions/process?minDelay=0")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/disruptions/process?minDelay=1441")).andExpect(status().isBadRequest());
    }

    @Test
    void compensationListFilters() throws Exception {
        when(travel.compensation(any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 50, 0, 0));
        mvc.perform(get("/api/v1/compensation?customerId=72&flightId=134")).andExpect(status().isOk());
        verify(travel).compensation(72, 134, 50, 0);
    }
}
