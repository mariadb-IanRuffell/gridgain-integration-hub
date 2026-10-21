package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import poc.api.model.Dto.Page;
import poc.api.model.TravelDto.Compensation;
import poc.api.repo.CustomerRepository;
import poc.api.repo.TravelRepository;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.NotFound;

/** Customer travel data and the endpoints that combine both source systems. */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Hub")
public class TravelHubController {
    private final ComputeService compute;
    private final CustomerRepository customers;
    private final TravelRepository travel;

    TravelHubController(ComputeService compute, CustomerRepository customers, TravelRepository travel) {
        this.compute = compute;
        this.customers = customers;
        this.travel = travel;
    }

    @GetMapping("/customers/{customerId}/trips")
    @Operation(summary = "A customer's trips (Oracle)",
        description = "Runs the customer-trips job (replaces sp_customer_trips): upcoming trips, past trips and statistics. "
            + "Returns an empty result for a customer with no bookings.")
    Map<String, Object> trips(@PathVariable int customerId) {
        customers.find(customerId).orElseThrow(() -> new NotFound("Customer", customerId));
        return compute.trips(customerId);
    }

    @GetMapping("/customers/{customerId}/hub")
    @Operation(summary = "Hub: one customer view across BOTH source systems",
        description = "Joins data that no single source holds. From MariaDB: profile, loyalty tier and points, completed orders. From Oracle: bookings, "
            + "miles, upcoming trips and the ones on delayed or cancelled flights. Also combines them: lifetime_value = retail spend + travel spend. "
            + "Sections: profile, retail, travel, upcoming_trips, at_risk_trips, value.")
    Map<String, Object> hub(@PathVariable int customerId) {
        return compute.customerHub(customerId);
    }

    @PostMapping("/disruptions/process")
    @Operation(summary = "Compute compensation for disrupted flights",
        description = "Runs the process-disruptions job (replaces sp_process_disruptions), one job per node: every segment on a cancelled flight "
            + "(100% of the fare) or on a flight delayed by at least minDelay minutes (75% from 720 min, 50% from 360, otherwise 25%) gets a compensation "
            + "row. Returns segments and total amount per reason.")
    List<Object> process(@RequestParam(defaultValue = "180") @Min(1) @Max(1440) int minDelay,
                         @RequestParam(required = false) @Parameter(description = "Compute as of this instant (ISO-8601); defaults to now", example = "2026-10-15T12:00:00Z") Instant asOf) {
        return compute.processDisruptions(minDelay, asOf == null ? null : asOf.toEpochMilli());
    }

    @GetMapping("/compensation")
    @Operation(summary = "List computed compensation", description = "Written by POST /disruptions/process. Filter by customer (MariaDB id) or flight.")
    Page<Compensation> compensation(@RequestParam(required = false) Integer customerId,
                                    @RequestParam(required = false) Integer flightId,
                                    @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
                                    @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return travel.compensation(customerId, flightId, limit, offset);
    }
}
