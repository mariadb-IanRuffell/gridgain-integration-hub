package poc.api.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import poc.api.model.Dto.*;
import poc.api.repo.CustomerRepository;
import poc.api.repo.OfferRepository;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.NotFound;

@WebMvcTest
@Import({OfferController.class, ApiExceptionHandler.class})
class OfferControllerTest {
    @Autowired MockMvc mvc;
    @MockBean OfferRepository offers;
    @MockBean CustomerRepository customers;
    @MockBean ComputeService compute;

    static final Offer OFFER = new Offer(72, 1, 21, "ANC-021", "Airport Transfer", "ANCILLARY", 1.2589, 8, new BigDecimal("180.00"), new BigDecimal("165.60"),
        "you fly LHR-DXB on 2026-10-23", Instant.parse("2026-10-19T09:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"));

    @Test
    void generateRunsTheJobWithDefaults() throws Exception {
        when(compute.generateOffers(anyInt(), any())).thenReturn(new OfferRun(240L, 240L, 720L, 5.03, 1.13, List.of(new CategoryCount("FLIGHT", 360L))));
        mvc.perform(post("/api/v1/offers/generate")).andExpect(status().isOk())
            .andExpect(jsonPath("$.offers").value(720)).andExpect(jsonPath("$.byCategory[0].category").value("FLIGHT"));
        verify(compute).generateOffers(3, null);
    }

    @Test
    void generateForOneCustomerWithTopN() throws Exception {
        when(compute.generateOffers(anyInt(), any())).thenReturn(new OfferRun(1L, 1L, 5L, 8.0, 1.0, List.of()));
        mvc.perform(post("/api/v1/offers/generate?topN=5&customerId=72")).andExpect(status().isOk());
        verify(compute).generateOffers(5, 72);
    }

    @Test
    void generateValidatesTopNAndMapsAnUnknownCustomer() throws Exception {
        mvc.perform(post("/api/v1/offers/generate?topN=0")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/offers/generate?topN=11")).andExpect(status().isBadRequest());
        verify(compute, never()).generateOffers(anyInt(), any());
        doThrow(new NotFound("Customer", 999)).when(compute).generateOffers(3, 999);
        mvc.perform(post("/api/v1/offers/generate?customerId=999")).andExpect(status().isNotFound());
    }

    @Test
    void listFiltersAndPages() throws Exception {
        when(offers.list(any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(OFFER), 10, 0, 1));
        mvc.perform(get("/api/v1/offers?customerId=72&category=ANCILLARY&limit=10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].sku").value("ANC-021")).andExpect(jsonPath("$.items[0].offerPrice").value(165.60))
            .andExpect(jsonPath("$.items[0].expiresAt").value("2026-11-02T09:00:00Z"));
        verify(offers).list(72, "ANCILLARY", 10, 0);
        mvc.perform(get("/api/v1/offers?category=CARS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/offers?limit=501")).andExpect(status().isBadRequest());
    }

    @Test
    void aCustomersOffers() throws Exception {
        when(customers.find(72)).thenReturn(Optional.of(CustomerControllerTest.customer(72)));
        when(offers.forCustomer(72)).thenReturn(List.of(OFFER));
        mvc.perform(get("/api/v1/customers/72/offers")).andExpect(status().isOk()).andExpect(jsonPath("$[0].reason").value("you fly LHR-DXB on 2026-10-23"));
        when(customers.find(73)).thenReturn(Optional.of(CustomerControllerTest.customer(73)));
        mvc.perform(get("/api/v1/customers/73/offers")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/customers/74/offers")).andExpect(status().isNotFound());
    }
}
