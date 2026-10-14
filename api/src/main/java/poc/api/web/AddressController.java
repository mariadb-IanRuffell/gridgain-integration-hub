package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import poc.api.model.Dto.Address;
import poc.api.model.Dto.AddressInput;
import poc.api.repo.AddressRepository;
import poc.api.repo.CustomerRepository;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/addresses")
@Tag(name = "Addresses")
public class AddressController {
    private final AddressRepository addresses;
    private final CustomerRepository customers;

    AddressController(AddressRepository addresses, CustomerRepository customers) {
        this.addresses = addresses;
        this.customers = customers;
    }

    private void requireCustomer(int customerId) {
        customers.find(customerId).orElseThrow(() -> new NotFound("Customer", customerId));
    }

    @GetMapping
    @Operation(summary = "List a customer's addresses")
    List<Address> list(@PathVariable int customerId) {
        requireCustomer(customerId);
        return addresses.list(customerId);
    }

    @GetMapping("/{addressId}")
    @Operation(summary = "Get an address")
    Address get(@PathVariable int customerId, @PathVariable int addressId) {
        return addresses.find(customerId, addressId).orElseThrow(() -> new NotFound("Address", addressId));
    }

    @PostMapping
    @Operation(summary = "Add an address", description = "Marking it primary clears the flag on the customer's other addresses.")
    ResponseEntity<Address> create(@PathVariable int customerId, @Valid @RequestBody AddressInput in) {
        requireCustomer(customerId);
        Address a = addresses.create(customerId, in);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(a.addressId()).toUri()).body(a);
    }

    @PutMapping("/{addressId}")
    @Operation(summary = "Replace an address")
    Address update(@PathVariable int customerId, @PathVariable int addressId, @Valid @RequestBody AddressInput in) {
        get(customerId, addressId);
        addresses.update(customerId, addressId, in);
        return get(customerId, addressId);
    }

    @DeleteMapping("/{addressId}")
    @Operation(summary = "Delete an address")
    ResponseEntity<Void> delete(@PathVariable int customerId, @PathVariable int addressId) {
        get(customerId, addressId);
        addresses.delete(customerId, addressId);
        return ResponseEntity.noContent().build();
    }
}
