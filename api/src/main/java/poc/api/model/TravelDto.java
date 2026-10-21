package poc.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Shapes for the travel / reservations data (replicated from Oracle). Customers live in the MariaDB CRM data; the two are joined by customer_id. */
public final class TravelDto {
    private TravelDto() {}

    public enum FlightStatus { SCHEDULED, DELAYED, CANCELLED, DEPARTED, ARRIVED }
    public enum BookingStatus { CONFIRMED, CANCELLED }

    public record Airport(String iata, String name, String city, String country, String tz) {}

    public record FlightInventory(String cabin, Integer seatsTotal, Integer seatsAvailable, BigDecimal baseFare) {}

    public record Flight(Integer flightId, String flightNo, String origin, String destination, Instant depTime, Instant arrTime,
                         Integer distanceKm, String aircraft, String status, Integer delayMinutes, List<FlightInventory> inventory) {}

    public record InventoryInput(
        @NotNull @Pattern(regexp = "ECONOMY|BUSINESS|FIRST") String cabin,
        @NotNull @Min(1) @Max(600) Integer seats,
        @NotNull @Min(0) BigDecimal baseFare) {}

    public record FlightInput(
        @NotBlank @Size(max = 8) String flightNo,
        @NotBlank @Size(min = 3, max = 3) String origin,
        @NotBlank @Size(min = 3, max = 3) String destination,
        @NotNull Instant depTime,
        @NotNull Instant arrTime,
        @NotNull @Min(1) Integer distanceKm,
        @Size(max = 10) String aircraft,
        @Schema(description = "Seat inventory per cabin. Required when creating; ignored by PUT (inventory changes with bookings)")
        @Valid List<InventoryInput> inventory) {}

    public record FlightEvent(Integer eventId, Integer flightId, String eventType, String detail, Instant eventTime) {}

    public record Booking(Integer bookingId, Integer customerId, String bookingRef, String status, Instant bookedAt, String channel,
                          BigDecimal totalFare, BigDecimal refunded, String currency, List<BookingSegment> segments) {}

    public record BookingSegment(Integer segmentId, Integer bookingId, Integer flightId, String cabin, String seatNo, String passengerName,
                                 BigDecimal fare, String status, Integer milesEarned) {}

    public record BookFlightRequest(
        @NotNull Integer customerId,
        @NotNull Integer flightId,
        @NotNull @Pattern(regexp = "ECONOMY|BUSINESS|FIRST") String cabin,
        @NotBlank @Size(max = 80) String passenger,
        @Schema(description = "Defaults to WEB", allowableValues = {"WEB", "APP", "CALL", "AGENT"})
        @Pattern(regexp = "WEB|APP|CALL|AGENT") String channel) {}

    public record BookFlightResult(Integer bookingId, String bookingRef, Integer customerId, Integer flightId, String cabin, String seatNo,
                                   BigDecimal fare, Integer milesEarned) {}

    public record CancelBookingResult(Integer bookingId, BigDecimal refund, Integer segmentsCancelled, String bookingStatus) {}

    public record DelayRequest(
        @NotNull @Min(1) @Max(1440) Integer minutes,
        @Size(max = 150) String reason) {}

    public record CancelFlightRequest(@Size(max = 150) String reason) {}

    public record FlightOpResult(Integer flightId, String status, Long affectedBookings) {}

    public record Compensation(Integer segmentId, Integer customerId, BigDecimal amount, String reason, Instant createdAt) {}
}
