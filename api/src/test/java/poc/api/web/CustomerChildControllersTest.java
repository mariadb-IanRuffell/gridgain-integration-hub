package poc.api.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import poc.api.model.Dto.*;
import poc.api.repo.*;

/** Addresses, interactions and loyalty: everything nested under a customer. They all 404 for an unknown customer. */
@WebMvcTest
@Import({AddressController.class, InteractionController.class, LoyaltyController.class, ApiExceptionHandler.class})
class CustomerChildControllersTest {
    @Autowired MockMvc mvc;
    @MockBean AddressRepository addresses;
    @MockBean InteractionRepository interactions;
    @MockBean LoyaltyRepository loyalty;
    @MockBean CustomerRepository customers;

    static final Address ADDR = new Address(3, 7, "HOME", "1 Palm Rd", "Dubai", "123", "AE", true);
    static final Interaction INT = new Interaction(9, 7, "CALL", "COMPLAINT", "Delayed baggage", -1, "OPEN", Instant.EPOCH, null);

    @BeforeEach
    void knownCustomer() {
        when(customers.find(7)).thenReturn(Optional.of(CustomerControllerTest.customer(7)));
        when(customers.find(404)).thenReturn(Optional.empty());
    }

    // ---- addresses

    @Test
    void listAddresses() throws Exception {
        when(addresses.list(7)).thenReturn(List.of(ADDR));
        mvc.perform(get("/api/v1/customers/7/addresses")).andExpect(status().isOk()).andExpect(jsonPath("$[0].city").value("Dubai"));
        mvc.perform(get("/api/v1/customers/404/addresses")).andExpect(status().isNotFound());
    }

    @Test
    void addressGetCreateUpdateDelete() throws Exception {
        String body = "{\"addrType\":\"HOME\",\"line1\":\"1 Palm Rd\",\"city\":\"Dubai\",\"country\":\"AE\"}";
        when(addresses.find(7, 3)).thenReturn(Optional.of(ADDR));
        when(addresses.find(7, 4)).thenReturn(Optional.empty());
        when(addresses.create(eq(7), any(AddressInput.class))).thenReturn(ADDR);

        mvc.perform(get("/api/v1/customers/7/addresses/3")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/7/addresses/4")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/customers/7/addresses").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/addresses/3")));
        mvc.perform(post("/api/v1/customers/404/addresses").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/customers/7/addresses").contentType(MediaType.APPLICATION_JSON)
            .content("{\"addrType\":\"OFFICE\",\"line1\":\"x\",\"city\":\"y\",\"country\":\"AE\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/customers/7/addresses/3").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(addresses).update(eq(7), eq(3), any(AddressInput.class));
        mvc.perform(delete("/api/v1/customers/7/addresses/3")).andExpect(status().isNoContent());
        verify(addresses).delete(7, 3);
    }

    // ---- interactions

    @Test
    void listInteractionsFiltersByStatus() throws Exception {
        when(interactions.list(eq(7), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(INT), 50, 0, 1));
        mvc.perform(get("/api/v1/customers/7/interactions?status=OPEN")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].subject").value("Delayed baggage"));
        verify(interactions).list(7, "OPEN", 50, 0);
        mvc.perform(get("/api/v1/customers/7/interactions?status=PENDING")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers/404/interactions")).andExpect(status().isNotFound());
    }

    @Test
    void interactionCrud() throws Exception {
        String body = "{\"channel\":\"CALL\",\"type\":\"COMPLAINT\",\"subject\":\"Delayed baggage\",\"sentiment\":-1}";
        when(interactions.find(7, 9)).thenReturn(Optional.of(INT));
        when(interactions.create(eq(7), any(InteractionInput.class))).thenReturn(INT);
        mvc.perform(post("/api/v1/customers/7/interactions").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(put("/api/v1/customers/7/interactions/9").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(interactions).update(eq(7), eq(9), any(InteractionInput.class), eq(INT));
        mvc.perform(delete("/api/v1/customers/7/interactions/9")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/customers/7/interactions/10")).andExpect(status().isNotFound());
    }

    @Test
    void interactionBodyValidation() throws Exception {
        mvc.perform(post("/api/v1/customers/7/interactions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"FAX\",\"type\":\"COMPLAINT\",\"sentiment\":2}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("channel"), org.hamcrest.Matchers.containsString("sentiment"))));
    }

    // ---- loyalty

    @Test
    void loyaltyTiers() throws Exception {
        when(loyalty.tiers()).thenReturn(List.of(new LoyaltyTier("GOLD", new BigDecimal("20000"), new BigDecimal("1.5"))));
        mvc.perform(get("/api/v1/loyalty-tiers")).andExpect(status().isOk()).andExpect(jsonPath("$[0].tier").value("GOLD"));
    }

    @Test
    void loyaltyTransactionsRequireTheCustomer() throws Exception {
        when(loyalty.transactions(7, 50, 0)).thenReturn(new Page<>(List.of(), 50, 0, 0));
        mvc.perform(get("/api/v1/customers/7/loyalty-transactions")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/404/loyalty-transactions")).andExpect(status().isNotFound());
    }

    @Test
    void scoreOr404() throws Exception {
        when(loyalty.score(7)).thenReturn(Optional.of(new CustomerScore(7, 35, "MEDIUM", Instant.EPOCH)));
        mvc.perform(get("/api/v1/customers/7/score")).andExpect(status().isOk()).andExpect(jsonPath("$.riskBand").value("MEDIUM"));
        mvc.perform(get("/api/v1/customers/8/score")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Churn score for customer 8 not found"));
    }
}
