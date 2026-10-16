package poc.api.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import poc.api.model.Dto.Customer;
import poc.api.model.Dto.CustomerInput;
import poc.api.model.Dto.Page;
import poc.api.repo.CustomerRepository;

@WebMvcTest
@Import({CustomerController.class, ApiExceptionHandler.class})
class CustomerControllerTest {
    @Autowired MockMvc mvc;
    @MockBean CustomerRepository customers;

    static Customer customer(int id) {
        return new Customer(id, "Aisha", "Rossi", "aisha@example.com", null, null, "AE", "STANDARD", "GOLD", 1200, true, "ACTIVE",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    }

    static final String BODY = "{\"firstName\":\"Aisha\",\"lastName\":\"Rossi\",\"email\":\"aisha@example.com\",\"nationality\":\"AE\"}";

    @Test
    void listPassesFiltersAndTrimsBlankValues() throws Exception {
        when(customers.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(customer(1)), 50, 0, 1));
        mvc.perform(get("/api/v1/customers?status=ACTIVE&tier=GOLD").param("email", "  ").param("q", " ros "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].customerId").value(1))
            .andExpect(jsonPath("$.items[0].loyaltyTier").value("GOLD"))
            .andExpect(jsonPath("$.total").value(1));
        verify(customers).list("ACTIVE", "GOLD", null, "ros", 50, 0);
    }

    @Test
    void listDefaultsToNoFilters() throws Exception {
        when(customers.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 50, 0, 0));
        mvc.perform(get("/api/v1/customers")).andExpect(status().isOk());
        verify(customers).list(null, null, null, null, 50, 0);
    }

    @Test
    void invalidEnumValueIsA400ThatListsTheAllowedValues() throws Exception {
        mvc.perform(get("/api/v1/customers?tier=string"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("Invalid value 'string' for parameter 'tier'"),
                org.hamcrest.Matchers.containsString("BLUE, SILVER, GOLD, PLATINUM"))));
        verifyNoInteractions(customers);
    }

    @Test
    void pagingBoundsAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/customers?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers?limit=501")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers?offset=-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(customers);
    }

    @Test
    void getReturnsTheCustomerOr404() throws Exception {
        when(customers.find(7)).thenReturn(Optional.of(customer(7)));
        when(customers.find(99)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/customers/7")).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("aisha@example.com"));
        mvc.perform(get("/api/v1/customers/99")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Customer 99 not found"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(customers.emailTaken("aisha@example.com", null)).thenReturn(false);
        when(customers.create(any(CustomerInput.class))).thenReturn(customer(1000000001));
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/customers/1000000001")))
            .andExpect(jsonPath("$.customerId").value(1000000001));
    }

    @Test
    void createWithADuplicateEmailIs409() throws Exception {
        when(customers.emailTaken("aisha@example.com", null)).thenReturn(true);
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("Email already in use: aisha@example.com"));
        verify(customers, never()).create(any());
    }

    @Test
    void createValidatesTheBody() throws Exception {
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"\",\"email\":\"not-an-email\",\"segment\":\"VIP\",\"nationality\":\"ARE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("firstName"), org.hamcrest.Matchers.containsString("lastName"),
                org.hamcrest.Matchers.containsString("email"), org.hamcrest.Matchers.containsString("segment"),
                org.hamcrest.Matchers.containsString("nationality"))));
        verifyNoInteractions(customers);
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content("{nope"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Malformed request body")));
    }

    @Test
    void updateChecksExistenceAndEmailOwnership() throws Exception {
        when(customers.find(7)).thenReturn(Optional.of(customer(7)));
        when(customers.emailTaken("aisha@example.com", 7)).thenReturn(false);
        mvc.perform(put("/api/v1/customers/7").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        verify(customers).update(eq(7), any(CustomerInput.class));

        when(customers.find(8)).thenReturn(Optional.empty());
        mvc.perform(put("/api/v1/customers/8").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());

        when(customers.find(9)).thenReturn(Optional.of(customer(9)));
        when(customers.emailTaken("aisha@example.com", 9)).thenReturn(true);
        mvc.perform(put("/api/v1/customers/9").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
    }

    @Test
    void deleteIsRefusedWhenTheCustomerHasOrders() throws Exception {
        when(customers.find(7)).thenReturn(Optional.of(customer(7)));
        when(customers.orderCount(7)).thenReturn(3L);
        mvc.perform(delete("/api/v1/customers/7")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("has orders")));
        verify(customers, never()).delete(anyInt());
    }

    @Test
    void deleteWithoutOrdersIs204() throws Exception {
        when(customers.find(7)).thenReturn(Optional.of(customer(7)));
        when(customers.orderCount(7)).thenReturn(0L);
        mvc.perform(delete("/api/v1/customers/7")).andExpect(status().isNoContent());
        verify(customers).delete(7);
    }
}
