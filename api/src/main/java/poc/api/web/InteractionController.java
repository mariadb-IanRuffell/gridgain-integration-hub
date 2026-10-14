package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import poc.api.model.Dto.Interaction;
import poc.api.model.Dto.InteractionInput;
import poc.api.model.Dto.InteractionStatus;
import poc.api.model.Dto.Page;
import poc.api.repo.CustomerRepository;
import poc.api.repo.InteractionRepository;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/interactions")
@Validated
@Tag(name = "Interactions")
public class InteractionController {
    private final InteractionRepository interactions;
    private final CustomerRepository customers;

    InteractionController(InteractionRepository interactions, CustomerRepository customers) {
        this.interactions = interactions;
        this.customers = customers;
    }

    private void requireCustomer(int customerId) {
        customers.find(customerId).orElseThrow(() -> new NotFound("Customer", customerId));
    }

    @GetMapping
    @Operation(summary = "List a customer's service interactions", description = "Newest first.")
    Page<Interaction> list(@PathVariable int customerId, @RequestParam(required = false) InteractionStatus status,
                           @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
                           @RequestParam(defaultValue = "0") @Min(0) int offset) {
        requireCustomer(customerId);
        return interactions.list(customerId, status == null ? null : status.name(), limit, offset);
    }

    @GetMapping("/{interactionId}")
    @Operation(summary = "Get an interaction")
    Interaction get(@PathVariable int customerId, @PathVariable int interactionId) {
        return interactions.find(customerId, interactionId).orElseThrow(() -> new NotFound("Interaction", interactionId));
    }

    @PostMapping
    @Operation(summary = "Log an interaction")
    ResponseEntity<Interaction> create(@PathVariable int customerId, @Valid @RequestBody InteractionInput in) {
        requireCustomer(customerId);
        Interaction i = interactions.create(customerId, in);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(i.interactionId()).toUri()).body(i);
    }

    @PutMapping("/{interactionId}")
    @Operation(summary = "Replace an interaction", description = "Closing it stamps closedAt; re-opening clears it.")
    Interaction update(@PathVariable int customerId, @PathVariable int interactionId, @Valid @RequestBody InteractionInput in) {
        Interaction existing = get(customerId, interactionId);
        interactions.update(customerId, interactionId, in, existing);
        return get(customerId, interactionId);
    }

    @DeleteMapping("/{interactionId}")
    @Operation(summary = "Delete an interaction")
    ResponseEntity<Void> delete(@PathVariable int customerId, @PathVariable int interactionId) {
        get(customerId, interactionId);
        interactions.delete(customerId, interactionId);
        return ResponseEntity.noContent().build();
    }
}
