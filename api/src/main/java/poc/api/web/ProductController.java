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
import poc.api.model.Dto.Page;
import poc.api.model.Dto.ProductCategory;
import poc.api.model.Dto.Product;
import poc.api.model.Dto.ProductInput;
import poc.api.repo.ProductRepository;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;

@RestController
@RequestMapping("/api/v1/products")
@Validated
@Tag(name = "Products")
public class ProductController {
    private final ProductRepository products;

    ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    @Operation(summary = "List products")
    Page<Product> list(@RequestParam(required = false) ProductCategory category,
                       @RequestParam(required = false) Boolean active,
                       @RequestParam(required = false) @io.swagger.v3.oas.annotations.Parameter(description = "Case-insensitive fragment of the name") String q,
                       @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
                       @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return products.list(category == null ? null : category.name(), active,
            q == null || q.isBlank() ? null : q.trim(), limit, offset);
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get a product")
    Product get(@PathVariable int productId) {
        return products.find(productId).orElseThrow(() -> new NotFound("Product", productId));
    }

    @PostMapping
    @Operation(summary = "Create a product", description = "SKU must be unique.")
    ResponseEntity<Product> create(@Valid @RequestBody ProductInput in) {
        if (products.skuTaken(in.sku(), null)) throw new Conflict("SKU already in use: " + in.sku());
        Product p = products.create(in);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(p.productId()).toUri()).body(p);
    }

    @PutMapping("/{productId}")
    @Operation(summary = "Replace a product", description = "Also the way to adjust price, stock or deactivate a product.")
    Product update(@PathVariable int productId, @Valid @RequestBody ProductInput in) {
        get(productId);
        if (products.skuTaken(in.sku(), productId)) throw new Conflict("SKU already in use: " + in.sku());
        products.update(productId, in);
        return get(productId);
    }

    @DeleteMapping("/{productId}")
    @Operation(summary = "Delete a product", description = "Refused with 409 if any order line references it (set active=false instead).")
    ResponseEntity<Void> delete(@PathVariable int productId) {
        get(productId);
        if (products.orderItemCount(productId) > 0)
            throw new Conflict("Product " + productId + " appears on orders; set active=false instead of deleting");
        products.delete(productId);
        return ResponseEntity.noContent().build();
    }
}
