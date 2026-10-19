package poc.api.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import poc.api.config.WebConfig;
import poc.api.service.DashboardService;
import poc.api.model.Dto.CategoryCount;
import poc.api.model.Dto.OfferSummary;
import poc.api.service.DashboardService.*;

@WebMvcTest
@Import({DashboardController.class, WebConfig.class, ApiExceptionHandler.class})
class DashboardControllerTest {
    @Autowired MockMvc mvc;
    @MockBean DashboardService dashboard;
    @MockBean poc.api.repo.OfferRepository offers;

    @Test
    void summaryIsSerialisedWithNullsOmitted() throws Exception {
        when(dashboard.summary()).thenReturn(new Summary(Instant.parse("2026-10-15T12:00:00Z"), new Cluster(3, List.of("node1", "node2", "node3")),
            List.of(new TableSize("customer", "sybase", 250, 250L, 0L), new TableSize("airport", "oracle", 18, null, null)),
            268, null, null, List.of(), new ApiStats(10, 5, 4, 1, 0, 0, 2.5)));
        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isOk())
            .andExpect(jsonPath("$.cluster.nodes").value(3))
            .andExpect(jsonPath("$.generatedAt").value("2026-10-15T12:00:00Z"))
            .andExpect(jsonPath("$.tables[0].difference").value(0))
            .andExpect(jsonPath("$.tables[1].sourceRows").doesNotExist())
            .andExpect(jsonPath("$.totalSourceRows").doesNotExist());
    }

    @Test
    void seriesPassesSince() throws Exception {
        when(dashboard.series(1234L)).thenReturn(new Series(2000, 9999L, List.of()));
        mvc.perform(get("/api/v1/dashboard/series?since=1234")).andExpect(status().isOk()).andExpect(jsonPath("$.intervalMs").value(2000));
        mvc.perform(get("/api/v1/dashboard/series?since=abc")).andExpect(status().isBadRequest());
        verify(dashboard).series(1234L);
    }

    @Test
    void offersSummaryForThePanel() throws Exception {
        when(offers.summary(8)).thenReturn(new OfferSummary(720, 240, 5.03, Instant.parse("2026-10-19T09:00:00Z"),
            List.of(new CategoryCount("FLIGHT", 360L)), List.of()));
        mvc.perform(get("/api/v1/dashboard/offers")).andExpect(status().isOk())
            .andExpect(jsonPath("$.offers").value(720)).andExpect(jsonPath("$.customers").value(240))
            .andExpect(jsonPath("$.byCategory[0].category").value("FLIGHT")).andExpect(jsonPath("$.lastGenerated").value("2026-10-19T09:00:00Z"));
    }

    @Test
    void rootForwardsToTheDashboardPage() throws Exception {
        mvc.perform(get("/")).andExpect(forwardedUrl("/dashboard.html"));
        mvc.perform(get("/dashboard")).andExpect(forwardedUrl("/dashboard.html"));
    }
}
