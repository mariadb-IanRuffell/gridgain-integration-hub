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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import poc.api.model.Dto.*;
import poc.api.repo.OrderRepository;
import poc.api.service.ComputeService;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;

@WebMvcTest
@Import({OrderController.class, ApiExceptionHandler.class})
class OrderControllerTest {
    @Autowired MockMvc mvc;
    @MockBean OrderRepository orders;
    @MockBean ComputeService compute;

    static Order order(String status) {
        return new Order(11, 7, Instant.EPOCH, status, "WEB", new BigDecimal("500.00"), "AED", List.of(), List.of());
    }

    @Test
    void listFiltersByCustomerAndStatus() throws Exception {
        when(orders.list(any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(order("COMPLETED")), 50, 0, 1));
        mvc.perform(get("/api/v1/orders?customerId=7&status=COMPLETED")).andExpect(status().isOk());
        verify(orders).list(7, "COMPLETED", 50, 0);
        mvc.perform(get("/api/v1/orders?status=SHIPPED")).andExpect(status().isBadRequest());
    }

    @Test
    void getOr404() throws Exception {
        when(orders.findDetail(11)).thenReturn(Optional.of(order("NEW")));
        mvc.perform(get("/api/v1/orders/11")).andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(500.00));
        mvc.perform(get("/api/v1/orders/12")).andExpect(status().isNotFound());
    }

    @Test
    void placeOrderRunsTheComputeJobAndReturns201() throws Exception {
        when(compute.placeOrder(any())).thenReturn(new PlaceOrderResult(1000000005, 7, new BigDecimal("1560.00"), 1560, "GOLD", "GOLD", 9000));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":7,\"items\":[{\"productId\":1,\"quantity\":2}]}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/orders/1000000005")))
            .andExpect(jsonPath("$.points").value(1560));
        verify(compute).placeOrder(argThat(r -> r.customerId() == 7 && r.items().size() == 1 && r.channel() == null));
    }

    @Test
    void placeOrderValidatesLinesAndMapsJobErrors() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":7,\"items\":[]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\":7,\"items\":[{\"productId\":1,\"quantity\":101}]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\":7,\"channel\":\"FAX\",\"items\":[{\"productId\":1,\"quantity\":1}]}")).andExpect(status().isBadRequest());

        doThrow(new Conflict("Insufficient stock for product 1")).when(compute).placeOrder(any());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\":7,\"items\":[{\"productId\":1,\"quantity\":1}]}")).andExpect(status().isConflict());
        doThrow(new NotFound("Customer", 7)).when(compute).placeOrder(any());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\":7,\"items\":[{\"productId\":1,\"quantity\":1}]}")).andExpect(status().isNotFound());
    }

    @Test
    void updateChangesOnlyTheChannel() throws Exception {
        when(orders.findDetail(11)).thenReturn(Optional.of(order("NEW")));
        mvc.perform(put("/api/v1/orders/11").contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"APP\"}")).andExpect(status().isOk());
        verify(orders).updateChannel(11, 7, "APP");
        mvc.perform(put("/api/v1/orders/11").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void onlyCancelledOrdersCanBeDeleted() throws Exception {
        when(orders.findDetail(11)).thenReturn(Optional.of(order("COMPLETED")));
        mvc.perform(delete("/api/v1/orders/11")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("Only CANCELLED orders can be deleted; order 11 is COMPLETED"));
        when(orders.findDetail(11)).thenReturn(Optional.of(order("CANCELLED")));
        mvc.perform(delete("/api/v1/orders/11")).andExpect(status().isNoContent());
        verify(orders).delete(11, 7);
    }
}
