package poc.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import poc.api.model.Dto.OfferSummary;
import poc.api.repo.OfferRepository;
import poc.api.service.DashboardService;
import poc.api.service.DashboardService.Series;
import poc.api.service.DashboardService.Summary;

/** Data behind the live dashboard page (/dashboard.html). These calls are not counted in the REST throughput graphs. */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    private final OfferRepository offers;

    DashboardController(DashboardService dashboard, OfferRepository offers) {
        this.dashboard = dashboard;
        this.offers = offers;
    }

    @GetMapping("/summary")
    @Operation(summary = "Table sizes, CDC status and REST totals",
        description = "Row counts per table in GridGain and (as last counted by the CDC reader) in MariaDB, CDC position, lag "
            + "and cumulative counters, REST request totals and the GridGain server nodes. Refreshed every 2-5 seconds.")
    Summary summary() {
        return dashboard.summary();
    }

    @GetMapping("/series")
    @Operation(summary = "Throughput time series for the live graphs",
        description = "One sample every 2 seconds for the last 15 minutes: REST requests/s (reads, writes, errors, average "
            + "latency) and CDC events/s (upserts, deletes), backlog and latency. Pass since=<epoch ms of the last sample "
            + "you have> to get only newer samples.")
    Series series(@RequestParam(defaultValue = "0") long since) {
        return dashboard.series(since);
    }

    @GetMapping("/offers")
    @Operation(summary = "Offer totals for the dashboard",
        description = "How many offers exist, for how many customers, the average discount, the split by category and the strongest offers.")
    OfferSummary offers() {
        return offers.summary(8);
    }
}
