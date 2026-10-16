package poc.api.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.NotFound;
import poc.api.web.ApiExceptions.Unavailable;

@WebMvcTest
@Import({OperationsController.class, ApiExceptionHandler.class})
class OperationsControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ComputeService compute;

    @Test
    void customer360ReturnsTheJobResult() throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("profile", List.of(Map.of("customer_id", 7)));
        when(compute.customer360(7)).thenReturn(r);
        mvc.perform(get("/api/v1/customers/7/360")).andExpect(status().isOk()).andExpect(jsonPath("$.profile[0].customer_id").value(7));
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        doThrow(new NotFound("Customer", 8)).when(compute).customer360(8);
        mvc.perform(get("/api/v1/customers/8/360")).andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void recalculate() throws Exception {
        when(compute.recalculateTier(7)).thenReturn(Map.of("tier_after", "GOLD"));
        mvc.perform(post("/api/v1/customers/7/loyalty/recalculate")).andExpect(status().isOk()).andExpect(jsonPath("$.tier_after").value("GOLD"));
    }

    @Test
    void scoringConvertsAsOfToEpochMillis() throws Exception {
        when(compute.scoreCustomers(any())).thenReturn(List.of());
        mvc.perform(post("/api/v1/scoring/run?asOf=2026-10-14T12:00:00Z")).andExpect(status().isOk());
        verify(compute).scoreCustomers(1_791_979_200_000L);
        mvc.perform(post("/api/v1/scoring/run")).andExpect(status().isOk());
        verify(compute).scoreCustomers(null);
    }

    @Test
    void anUnreachableClusterIs503() throws Exception {
        doThrow(new Unavailable("GridGain compute call failed: down", new RuntimeException("x"))).when(compute).customer360(7);
        mvc.perform(get("/api/v1/customers/7/360")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("GridGain compute call failed: down"));
    }
}
