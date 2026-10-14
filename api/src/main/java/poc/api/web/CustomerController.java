package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import poc.api.model.Dto.Customer;
import poc.api.model.Dto.CustomerInput;
import poc.api.model.Dto.CustomerStatus;
import poc.api.model.Dto.Tier;
import poc.api.model.Dto.Page;
import poc.api.repo.CustomerRepository;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1/customers")
@Validated
@Tag(name = "Customers")
public class CustomerController {
    private final CustomerRepository customers;

    CustomerController(CustomerRepository customers) {
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "List customers", description = "Filter by status, loyalty tier, exact email, or a name fragment; paged.")
    Page<Customer> list(
        @RequestParam(required = false) CustomerStatus status,
        @RequestParam(required = false) Tier tier,
        @RequestParam(required = false) @Parameter(description = "Exact email, case-insensitive") String email,
        @RequestParam(required = false) @Parameter(description = "Case-insensitive fragment of first or last name") String q,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return customers.list(status == null ? null : status.name(), tier == null ? null : tier.name(),
            blankToNull(email), blankToNull(q), limit, offset);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get a customer")
    Customer get(@PathVariable int customerId) {
        return customers.find(customerId).orElseThrow(() -> new NotFound("Customer", customerId));
    }

    @PostMapping
    @Operation(summary = "Create a customer",
        description = "New customers start at tier BLUE with 0 points. Email must be unique (case-insensitive).")
    ResponseEntity<Customer> create(@Valid @RequestBody CustomerInput in) {
        if (customers.emailTaken(in.email(), null)) throw new Conflict("Email already in use: " + in.email());
        Customer created = customers.create(in);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.customerId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{customerId}")
    @Operation(summary = "Replace a customer's editable fields",
        description = "Loyalty tier and points are not editable here: they change through orders and the recalculate operation.")
    Customer update(@PathVariable int customerId, @Valid @RequestBody CustomerInput in) {
        get(customerId);
        if (customers.emailTaken(in.email(), customerId)) throw new Conflict("Email already in use: " + in.email());
        customers.update(customerId, in);
        return get(customerId);
    }

    @DeleteMapping("/{customerId}")
    @Operation(summary = "Delete a customer",
        description = "Refused with 409 if the customer has orders (set status INACTIVE instead). Otherwise also removes "
            + "their addresses, interactions, loyalty transactions and score.")
    ResponseEntity<Void> delete(@PathVariable int customerId) {
        get(customerId);
        if (customers.orderCount(customerId) > 0)
            throw new Conflict("Customer " + customerId + " has orders; set status to INACTIVE instead of deleting");
        customers.delete(customerId);
        return ResponseEntity.noContent().build();
    }
}
