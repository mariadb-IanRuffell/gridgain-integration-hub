package poc.api.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import poc.api.model.Dto.Page;
import poc.api.model.Dto.Product;
import poc.api.model.Dto.ProductInput;
import poc.api.repo.ProductRepository;

@WebMvcTest
@Import({ProductController.class, ApiExceptionHandler.class})
class ProductControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ProductRepository products;

    static final Product P = new Product(5, "LOU-011", "Lounge Day Pass", "LOUNGE", new BigDecimal("220.00"), 100, true);
    static final String BODY = "{\"sku\":\"LOU-011\",\"name\":\"Lounge Day Pass\",\"category\":\"LOUNGE\",\"unitPrice\":220,\"stockQty\":100}";

    @Test
    void listFilters() throws Exception {
        when(products.list(any(), any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(P), 10, 5, 1));
        mvc.perform(get("/api/v1/products?category=LOUNGE&active=true&limit=10&offset=5").param("q", " day "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sku").value("LOU-011")).andExpect(jsonPath("$.offset").value(5));
        verify(products).list("LOUNGE", true, "day", 10, 5);
        mvc.perform(get("/api/v1/products?category=CRUISE")).andExpect(status().isBadRequest());
    }

    @Test
    void getOr404() throws Exception {
        when(products.find(5)).thenReturn(Optional.of(P));
        mvc.perform(get("/api/v1/products/5")).andExpect(status().isOk()).andExpect(jsonPath("$.unitPrice").value(220.00));
        mvc.perform(get("/api/v1/products/6")).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Product 6 not found"));
    }

    @Test
    void createAndSkuConflict() throws Exception {
        when(products.create(any(ProductInput.class))).thenReturn(P);
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/products/5")));
        when(products.skuTaken("LOU-011", null)).thenReturn(true);
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("SKU already in use: LOU-011"));
    }

    @Test
    void createRejectsNegativePricesAndUnknownCategories() throws Exception {
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"X\",\"name\":\"n\",\"category\":\"CRUISE\",\"unitPrice\":-1,\"stockQty\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("category"), org.hamcrest.Matchers.containsString("unitPrice"))));
    }

    @Test
    void deleteIsRefusedForProductsOnOrders() throws Exception {
        when(products.find(5)).thenReturn(Optional.of(P));
        when(products.orderItemCount(5)).thenReturn(2L);
        mvc.perform(delete("/api/v1/products/5")).andExpect(status().isConflict());
        when(products.orderItemCount(5)).thenReturn(0L);
        mvc.perform(delete("/api/v1/products/5")).andExpect(status().isNoContent());
        verify(products, times(1)).delete(5);
    }

    @Test
    void updateChecksSkuOwnership() throws Exception {
        when(products.find(5)).thenReturn(Optional.of(P));
        mvc.perform(put("/api/v1/products/5").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        verify(products).update(eq(5), any(ProductInput.class));
        when(products.skuTaken("LOU-011", 5)).thenReturn(true);
        mvc.perform(put("/api/v1/products/5").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
    }
}
