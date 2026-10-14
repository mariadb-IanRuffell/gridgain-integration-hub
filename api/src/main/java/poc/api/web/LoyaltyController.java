package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import poc.api.model.Dto.CustomerScore;
import poc.api.model.Dto.LoyaltyTier;
import poc.api.model.Dto.LoyaltyTransaction;
import poc.api.model.Dto.Page;
import poc.api.repo.CustomerRepository;
import poc.api.repo.LoyaltyRepository;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Loyalty")
public class LoyaltyController {
    private final LoyaltyRepository loyalty;
    private final CustomerRepository customers;

    LoyaltyController(LoyaltyRepository loyalty, CustomerRepository customers) {
        this.loyalty = loyalty;
        this.customers = customers;
    }

    @GetMapping("/loyalty-tiers")
    @Operation(summary = "List loyalty tiers", description = "Spend thresholds (trailing 12 months) and points multipliers.")
    List<LoyaltyTier> tiers() {
        return loyalty.tiers();
    }

    @GetMapping("/customers/{customerId}/loyalty-transactions")
    @Operation(summary = "A customer's points ledger", description = "Newest first. Written by the place-order job.")
    Page<LoyaltyTransaction> transactions(@PathVariable int customerId,
                                          @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
                                          @RequestParam(defaultValue = "0") @Min(0) int offset) {
        customers.find(customerId).orElseThrow(() -> new NotFound("Customer", customerId));
        return loyalty.transactions(customerId, limit, offset);
    }

    @GetMapping("/customers/{customerId}/score")
    @Operation(summary = "A customer's latest churn score", description = "Produced by POST /scoring/run; 404 if not yet scored.")
    CustomerScore score(@PathVariable int customerId) {
        return loyalty.score(customerId).orElseThrow(() -> new NotFound("Churn score for customer", customerId));
    }
}
