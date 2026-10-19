package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import poc.api.model.Dto.Offer;
import poc.api.model.Dto.OfferRun;
import poc.api.model.Dto.Page;
import poc.api.model.Dto.ProductCategory;
import poc.api.repo.CustomerRepository;
import poc.api.repo.OfferRepository;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.NotFound;

/** Personalised offers, generated inside the cluster from each customer's buying behaviour. */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Offers")
public class OfferController {
    private final OfferRepository offers;
    private final CustomerRepository customers;
    private final ComputeService compute;

    OfferController(OfferRepository offers, CustomerRepository customers, ComputeService compute) {
        this.offers = offers;
        this.customers = customers;
        this.compute = compute;
    }

    @PostMapping("/offers/generate")
    @Operation(summary = "Generate offers",
        description = "Runs the recommender job on every cluster node; each node scores the products for the customers it owns from their order history "
            + "(recency-weighted category affinity, what similar-tier customers buy, repurchase of consumables, an upcoming or disrupted flight from the "
            + "Oracle travel data) and writes the top offers to customer_offer, replacing the customer's previous ones. Discounts come from loyalty tier, "
            + "churn risk and flight disruption (max 20%). Pass customerId to generate for one customer only (404 if unknown or inactive). "
            + "Nothing leaves the cluster.")
    OfferRun generate(@RequestParam(defaultValue = "3") @Min(1) @Max(10) @Parameter(description = "Offers per customer") int topN,
                      @RequestParam(required = false) Integer customerId) {
        return compute.generateOffers(topN, customerId);
    }

    @GetMapping("/offers")
    @Operation(summary = "List offers", description = "Filter by customer or product category; ordered by customer, then rank.")
    Page<Offer> list(@RequestParam(required = false) Integer customerId,
                     @RequestParam(required = false) ProductCategory category,
                     @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
                     @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return offers.list(customerId, category == null ? null : category.name(), limit, offset);
    }

    @GetMapping("/customers/{customerId}/offers")
    @Operation(summary = "A customer's current offers", description = "Best first. Empty until offers have been generated for the customer.")
    List<Offer> forCustomer(@PathVariable int customerId) {
        customers.find(customerId).orElseThrow(() -> new NotFound("Customer", customerId));
        return offers.forCustomer(customerId);
    }
}
