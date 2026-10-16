package poc.api.model;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import poc.api.model.Dto.*;
import poc.api.model.TravelDto.*;

class DtoValidationTest {
    static final Validator V = Validation.buildDefaultValidatorFactory().getValidator();

    static Set<String> violations(Object o) {
        return V.validate(o).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    static CustomerInput customer(String first, String email, String nationality, String segment, String status) {
        return new CustomerInput(first, "Rossi", email, null, null, nationality, segment, null, status);
    }

    @Test
    void validCustomerHasNoViolations() {
        assertTrue(violations(customer("Aisha", "a@example.com", "AE", "PREMIUM", "ACTIVE")).isEmpty());
        assertTrue(violations(customer("Aisha", "a@example.com", null, null, null)).isEmpty());   // optional fields may be omitted
    }

    @Test
    void customerFieldRules() {
        assertEquals(Set.of("firstName"), violations(customer(" ", "a@example.com", "AE", null, null)));
        assertEquals(Set.of("email"), violations(customer("A", "nope", "AE", null, null)));
        assertEquals(Set.of("nationality"), violations(customer("A", "a@example.com", "ARE", null, null)));
        assertEquals(Set.of("segment"), violations(customer("A", "a@example.com", "AE", "VIP", null)));
        assertEquals(Set.of("status"), violations(customer("A", "a@example.com", "AE", null, "DELETED")));
        assertEquals(Set.of("firstName"), violations(customer("x".repeat(61), "a@example.com", "AE", null, null)));
    }

    @Test
    void addressRules() {
        assertTrue(violations(new AddressInput("HOME", "1 Rd", "Dubai", null, "AE", null)).isEmpty());
        assertEquals(Set.of("addrType"), violations(new AddressInput("OFFICE", "1 Rd", "Dubai", null, "AE", null)));
        assertEquals(Set.of("addrType", "line1", "city", "country"), violations(new AddressInput(null, "", "", null, "A", null)));
    }

    @Test
    void productRules() {
        assertTrue(violations(new ProductInput("SKU1", "Name", "HOTEL", BigDecimal.TEN, 0, null)).isEmpty());
        assertEquals(Set.of("unitPrice", "stockQty"), violations(new ProductInput("SKU1", "Name", "HOTEL", new BigDecimal("-0.01"), -1, null)));
        assertEquals(Set.of("category"), violations(new ProductInput("SKU1", "Name", "CRUISE", BigDecimal.ONE, 1, null)));
        assertEquals(Set.of("sku"), violations(new ProductInput("x".repeat(21), "Name", "HOTEL", BigDecimal.ONE, 1, null)));
    }

    @Test
    void orderRequestRules() {
        OrderLine ok = new OrderLine(1, 2);
        assertTrue(violations(new PlaceOrderRequest(7, null, null, List.of(ok))).isEmpty());
        assertEquals(Set.of("items"), violations(new PlaceOrderRequest(7, null, null, List.of())));
        assertEquals(Set.of("customerId"), violations(new PlaceOrderRequest(null, null, null, List.of(ok))));
        assertEquals(Set.of("channel", "paymentMethod"), violations(new PlaceOrderRequest(7, "FAX", "CHEQUE", List.of(ok))));
        assertEquals(Set.of("items[0].quantity"), violations(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(1, 0)))));
        assertEquals(Set.of("items[0].quantity"), violations(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(1, 101)))));
        assertEquals(Set.of("items[0].productId"), violations(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(null, 1)))));
    }

    @Test
    void interactionRules() {
        assertTrue(violations(new InteractionInput("CALL", "COMPLAINT", "s", -1, "OPEN")).isEmpty());
        assertEquals(Set.of("channel", "type", "sentiment"), violations(new InteractionInput("FAX", "RANT", "s", 2, null)));
        assertEquals(Set.of("sentiment"), violations(new InteractionInput("CALL", "COMPLAINT", "s", -2, null)));
        assertEquals(Set.of("status"), violations(new InteractionInput("CALL", "COMPLAINT", "s", 0, "PENDING")));
    }

    @Test
    void flightRules() {
        Instant d = Instant.parse("2026-11-01T08:00:00Z");
        InventoryInput inv = new InventoryInput("ECONOMY", 300, BigDecimal.TEN);
        assertTrue(violations(new FlightInput("XA1", "DXB", "LHR", d, d.plusSeconds(60), 5470, "A380", List.of(inv))).isEmpty());
        assertEquals(Set.of("origin", "destination", "distanceKm"),
            violations(new FlightInput("XA1", "DX", "LHRX", d, d.plusSeconds(60), 0, null, null)));
        assertEquals(Set.of("inventory[0].cabin", "inventory[0].seats", "inventory[0].baseFare"),
            violations(new FlightInput("XA1", "DXB", "LHR", d, d, 1, null, List.of(new InventoryInput("PREMIUM", 601, new BigDecimal("-1"))))));
    }

    @Test
    void bookingAndDelayRules() {
        assertTrue(violations(new BookFlightRequest(1, 2, "FIRST", "A B", "APP")).isEmpty());
        assertEquals(Set.of("customerId", "flightId", "cabin", "passenger"), violations(new BookFlightRequest(null, null, null, null, null)));
        assertEquals(Set.of("cabin", "channel"), violations(new BookFlightRequest(1, 2, "PREMIUM", "A", "FAX")));
        assertEquals(Set.of("passenger"), violations(new BookFlightRequest(1, 2, "FIRST", "x".repeat(81), null)));
        assertTrue(violations(new DelayRequest(1440, null)).isEmpty());
        assertEquals(Set.of("minutes"), violations(new DelayRequest(0, null)));
        assertEquals(Set.of("minutes"), violations(new DelayRequest(1441, null)));
        assertEquals(Set.of("minutes"), violations(new DelayRequest(null, null)));
        assertEquals(Set.of("reason"), violations(new CancelFlightRequest("x".repeat(151))));
    }

    @Test
    void filterEnumsExposeTheAllowedValues() {
        assertEquals(List.of("BLUE", "SILVER", "GOLD", "PLATINUM"), List.of(Tier.values()).stream().map(Enum::name).toList());
        assertEquals(List.of("ACTIVE", "INACTIVE"), List.of(CustomerStatus.values()).stream().map(Enum::name).toList());
        assertEquals(List.of("NEW", "COMPLETED", "CANCELLED"), List.of(OrderStatus.values()).stream().map(Enum::name).toList());
        assertEquals(List.of("OPEN", "CLOSED"), List.of(InteractionStatus.values()).stream().map(Enum::name).toList());
        assertEquals(6, ProductCategory.values().length);
        assertEquals(List.of("SCHEDULED", "DELAYED", "CANCELLED", "DEPARTED", "ARRIVED"), List.of(FlightStatus.values()).stream().map(Enum::name).toList());
        assertEquals(List.of("CONFIRMED", "CANCELLED"), List.of(BookingStatus.values()).stream().map(Enum::name).toList());
    }
}
