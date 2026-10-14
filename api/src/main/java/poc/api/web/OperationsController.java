package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import poc.api.service.ComputeService;

/** Business operations that run as GridGain compute jobs (replacing Sybase stored procedures). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Operations")
public class OperationsController {
    private final ComputeService compute;

    OperationsController(ComputeService compute) {
        this.compute = compute;
    }

    @GetMapping("/customers/{customerId}/360")
    @Operation(summary = "Customer 360 view",
        description = "Runs the customer-360 job on the node that owns the customer (replaces sp_customer_360). "
            + "Returns six sections: profile, order_summary, recent_orders, top_categories, interactions, loyalty. "
            + "Each section is a list of rows (objects with snake_case column names).")
    Map<String, Object> customer360(@PathVariable int customerId) {
        return compute.customer360(customerId);
    }

    @PostMapping("/customers/{customerId}/loyalty/recalculate")
    @Operation(summary = "Recalculate a customer's loyalty tier",
        description = "Derives the tier from trailing-12-month completed spend and updates the customer if it changed "
            + "(replaces sp_recalc_loyalty_tier).")
    Map<String, Object> recalculate(@PathVariable int customerId) {
        return compute.recalculateTier(customerId);
    }

    @PostMapping("/scoring/run")
    @Operation(summary = "Score all active customers for churn risk",
        description = "Runs one job per cluster node; each scores the customers it owns and upserts customer_score "
            + "(replaces sp_score_customers). Returns the number of customers and average score per risk band.")
    List<Object> score(@RequestParam(required = false)
                       @Parameter(description = "Score as of this instant (ISO-8601); defaults to now", example = "2026-10-14T12:00:00Z")
                       Instant asOf) {
        return compute.scoreCustomers(asOf == null ? null : asOf.toEpochMilli());
    }
}
