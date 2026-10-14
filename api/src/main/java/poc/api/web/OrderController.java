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
import poc.api.model.Dto.Order;
import poc.api.model.Dto.OrderStatus;
import poc.api.model.Dto.OrderUpdate;
import poc.api.model.Dto.Page;
import poc.api.model.Dto.PlaceOrderRequest;
import poc.api.model.Dto.PlaceOrderResult;
import poc.api.repo.OrderRepository;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1/orders")
@Validated
@Tag(name = "Orders")
public class OrderController {
    private final OrderRepository orders;
    private final ComputeService compute;

    OrderController(OrderRepository orders, ComputeService compute) {
        this.orders = orders;
        this.compute = compute;
    }

    @GetMapping
    @Operation(summary = "List orders", description = "Newest first. Line items and payments are only included by GET /orders/{id}.")
    Page<Order> list(@RequestParam(required = false) Integer customerId,
                     @RequestParam(required = false) OrderStatus status,
                     @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
                     @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return orders.list(customerId, status == null ? null : status.name(), limit, offset);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get an order with its line items and payments")
    Order get(@PathVariable int orderId) {
        return orders.findDetail(orderId).orElseThrow(() -> new NotFound("Order", orderId));
    }

    @PostMapping
    @Operation(summary = "Place an order",
        description = "Runs the place-order compute job: one transaction on the customer's node that checks stock, "
            + "decrements it, creates the order, line items and payment, awards loyalty points at the customer's current "
            + "tier and recalculates the tier. All-or-nothing: if any line fails nothing changes. "
            + "409 = insufficient stock, 404 = unknown or inactive customer, 400 = bad quantity or unknown product.")
    ResponseEntity<PlaceOrderResult> place(@Valid @RequestBody PlaceOrderRequest req) {
        PlaceOrderResult r = compute.placeOrder(req);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(r.orderId()).toUri()).body(r);
    }

    @PutMapping("/{orderId}")
    @Operation(summary = "Change an order's sales channel",
        description = "Totals, items and status are produced by the place-order job and are not editable.")
    Order update(@PathVariable int orderId, @Valid @RequestBody OrderUpdate in) {
        Order o = get(orderId);
        orders.updateChannel(orderId, o.customerId(), in.channel());
        return get(orderId);
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Delete a cancelled order", description = "Only CANCELLED orders can be deleted (409 otherwise).")
    ResponseEntity<Void> delete(@PathVariable int orderId) {
        Order o = get(orderId);
        if (!"CANCELLED".equals(o.status()))
            throw new Conflict("Only CANCELLED orders can be deleted; order " + orderId + " is " + o.status());
        orders.delete(orderId, o.customerId());
        return ResponseEntity.noContent().build();
    }
}
