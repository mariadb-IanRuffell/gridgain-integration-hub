package poc.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Request and response shapes. Output records mirror the GridGain tables; *Input records are request bodies. */
public final class Dto {
    private Dto() {}

    public record Page<T>(List<T> items, int limit, int offset, long total) {}

    // Filter values. Declared as enums so the OpenAPI spec lists the allowed values (dropdowns in Swagger UI)
    // and an invalid value is a 400 instead of a silently empty result.
    public enum CustomerStatus { ACTIVE, INACTIVE }
    public enum Tier { BLUE, SILVER, GOLD, PLATINUM }
    public enum ProductCategory { FLIGHT, HOTEL, LOUNGE, UPGRADE, INSURANCE, ANCILLARY }
    public enum OrderStatus { NEW, COMPLETED, CANCELLED }
    public enum InteractionStatus { OPEN, CLOSED }

    // ------------------------------------------------------------------ customers

    public record Customer(Integer customerId, String firstName, String lastName, String email, String phone,
                           LocalDate dateOfBirth, String nationality, String segment, String loyaltyTier,
                           Integer loyaltyPoints, Boolean marketingOptIn, String status,
                           Instant createdAt, Instant updatedAt) {}

    public record CustomerInput(
        @NotBlank @Size(max = 60) String firstName,
        @NotBlank @Size(max = 60) String lastName,
        @NotBlank @Email @Size(max = 120) String email,
        @Size(max = 30) String phone,
        LocalDate dateOfBirth,
        @Schema(description = "ISO 3166-1 alpha-2 country code", example = "AE") @Size(min = 2, max = 2) String nationality,
        @Schema(description = "Defaults to STANDARD", allowableValues = {"STANDARD", "PREMIUM", "CORPORATE"})
        @Pattern(regexp = "STANDARD|PREMIUM|CORPORATE") String segment,
        @Schema(description = "Defaults to false") Boolean marketingOptIn,
        @Schema(description = "Defaults to ACTIVE", allowableValues = {"ACTIVE", "INACTIVE"})
        @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

    // ------------------------------------------------------------------ addresses

    public record Address(Integer addressId, Integer customerId, String addrType, String line1, String city,
                          String postcode, String country, Boolean primary) {}

    public record AddressInput(
        @NotNull @Pattern(regexp = "HOME|BILLING|WORK") String addrType,
        @NotBlank @Size(max = 120) String line1,
        @NotBlank @Size(max = 60) String city,
        @Size(max = 20) String postcode,
        @NotBlank @Size(min = 2, max = 2) String country,
        @Schema(description = "Making an address primary clears the flag on the customer's other addresses")
        Boolean primary) {}

    // ------------------------------------------------------------------ products

    public record Product(Integer productId, String sku, String name, String category, BigDecimal unitPrice,
                          Integer stockQty, Boolean active) {}

    public record ProductInput(
        @NotBlank @Size(max = 20) String sku,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Pattern(regexp = "FLIGHT|HOTEL|LOUNGE|UPGRADE|INSURANCE|ANCILLARY") String category,
        @NotNull @Min(0) BigDecimal unitPrice,
        @NotNull @Min(0) Integer stockQty,
        @Schema(description = "Defaults to true") Boolean active) {}

    // ------------------------------------------------------------------ orders

    public record Order(Integer orderId, Integer customerId, Instant orderDate, String status, String channel,
                        BigDecimal totalAmount, String currency, List<OrderItem> items, List<Payment> payments) {}

    public record OrderItem(Integer orderItemId, Integer orderId, Integer productId, Integer qty,
                            BigDecimal unitPrice, BigDecimal lineTotal) {}

    public record Payment(Integer paymentId, Integer orderId, String method, BigDecimal amount, String status,
                          Instant paidAt) {}

    public record OrderLine(@NotNull Integer productId, @NotNull @Min(1) @Max(100) Integer quantity) {}

    public record PlaceOrderRequest(
        @NotNull Integer customerId,
        @Schema(description = "Defaults to WEB", allowableValues = {"WEB", "APP", "CALL", "AGENT"})
        @Pattern(regexp = "WEB|APP|CALL|AGENT") String channel,
        @Schema(description = "Defaults to CARD", allowableValues = {"CARD", "POINTS", "WALLET"})
        @Pattern(regexp = "CARD|POINTS|WALLET") String paymentMethod,
        @NotEmpty @Valid List<OrderLine> items) {}

    public record PlaceOrderResult(Integer orderId, Integer customerId, BigDecimal totalAmount, Integer points,
                                   String tierBefore, String tierAfter, Integer loyaltyPoints) {}

    public record OrderUpdate(@NotNull @Pattern(regexp = "WEB|APP|CALL|AGENT") String channel) {}

    // ------------------------------------------------------------------ interactions

    public record Interaction(Integer interactionId, Integer customerId, String channel, String type, String subject,
                              Integer sentiment, String status, Instant openedAt, Instant closedAt) {}

    public record InteractionInput(
        @NotNull @Pattern(regexp = "CALL|EMAIL|CHAT|SOCIAL") String channel,
        @NotNull @Pattern(regexp = "COMPLAINT|ENQUIRY|FEEDBACK|BOOKING_CHANGE") String type,
        @Size(max = 200) String subject,
        @Schema(description = "-1 negative, 0 neutral, 1 positive; defaults to 0") @Min(-1) @Max(1) Integer sentiment,
        @Schema(description = "Defaults to OPEN. Closing sets closedAt") @Pattern(regexp = "OPEN|CLOSED") String status) {}

    // ------------------------------------------------------------------ loyalty (read-only)

    public record LoyaltyTransaction(Integer txnId, Integer customerId, Integer orderId, Integer points,
                                     String reason, Instant txnDate) {}

    public record LoyaltyTier(String tier, BigDecimal minSpend, BigDecimal pointsMultiplier) {}

    public record CustomerScore(Integer customerId, Integer churnScore, String riskBand, Instant scoredAt) {}
}
