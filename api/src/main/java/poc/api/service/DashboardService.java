package poc.api.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import poc.api.metrics.RequestMetrics;
import poc.api.repo.DashboardRepository;
import poc.api.repo.DashboardRepository.CdcRow;
import poc.api.repo.DashboardRepository.SybaseCount;

/**
 * Samples REST counters (in-process) and CDC counters (published by the CDC reader into GridGain) every 2 seconds,
 * turns the cumulative values into per-second rates, and keeps the last 15 minutes in memory for the graphs.
 */
@Service
public class DashboardService {
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    static final int INTERVAL_MS = 2_000, KEEP = 450;           // 450 x 2 s = 15 minutes
    private static final long CDC_STALE_MS = 10_000;            // the reader heartbeats every 2 s

    public record Sample(long t, double apiReqPerSec, double apiReadsPerSec, double apiWritesPerSec,
                         double apiErrorsPerSec, double apiAvgMs, double cdcEventsPerSec, double cdcUpsertsPerSec,
                         double cdcDeletesPerSec, long cdcPending, long cdcLatencyMs) {}

    public record Series(int intervalMs, long serverTime, List<Sample> samples) {}

    public record TableSize(String name, long gridgainRows, Long sybaseRows, Long difference) {}

    public record Cluster(int nodes, List<String> nodeIds) {}

    public record CdcStatus(boolean running, Instant lastUpdate, Instant startedAt, long lastChangeId, long pendingEvents,
                            long totalEvents, long totalUpserts, long totalDeletes, long batches, int lastBatchEvents,
                            long lastBatchMs, long lastLatencyMs, Instant sybaseCountsAt) {}

    public record ApiStats(long uptimeSeconds, long requests, long reads, long writes, long clientErrors,
                           long serverErrors, double avgMs) {}

    public record Summary(Instant generatedAt, Cluster cluster, List<TableSize> tables, long totalGridgainRows,
                          Long totalSybaseRows, CdcStatus cdc, ApiStats api) {}

    private final RequestMetrics metrics;
    private final DashboardRepository repo;
    private final Deque<Sample> samples = new ArrayDeque<>();

    private long prevT;
    private RequestMetrics.Snapshot prevApi;
    private CdcRow prevCdc;
    private double[] cdcRates = {0, 0, 0};   // events, upserts, deletes per second
    private volatile Summary summary;
    private long lastTablesMs;
    private List<TableSize> tables = List.of();
    private Instant sybaseCountsAt;
    private Cluster cluster = new Cluster(0, List.of());

    DashboardService(RequestMetrics metrics, DashboardRepository repo) {
        this.metrics = metrics;
        this.repo = repo;
    }

    @Scheduled(fixedRate = INTERVAL_MS, initialDelay = 3_000)
    void sample() {
        long now = System.currentTimeMillis();
        RequestMetrics.Snapshot api = metrics.snapshot();
        CdcRow cdc = null;
        try {
            cdc = repo.cdcStatus().orElse(null);
            if (now - lastTablesMs >= 1_500) refreshTables(now);
        } catch (RuntimeException e) {
            log.debug("dashboard sampling: GridGain not readable: {}", e.getMessage());
        }

        if (prevApi != null) {
            double dt = Math.max((now - prevT) / 1000.0, 0.001);
            long dReq = api.requests() - prevApi.requests();
            double avgMs = dReq > 0 ? (api.nanos() - prevApi.nanos()) / 1e6 / dReq : 0;
            double errors = (api.clientErrors() + api.serverErrors() - prevApi.clientErrors() - prevApi.serverErrors()) / dt;
            cdcRates = cdcRates(now, cdc);
            Sample s = new Sample(now, dReq / dt, (api.reads() - prevApi.reads()) / dt, (api.writes() - prevApi.writes()) / dt,
                errors, avgMs, cdcRates[0], cdcRates[1], cdcRates[2],
                cdc != null ? cdc.pendingEvents() : 0, cdc != null ? cdc.lastLatencyMs() : 0);
            synchronized (samples) {
                samples.addLast(s);
                while (samples.size() > KEEP) samples.removeFirst();
            }
        }
        prevT = now;
        prevApi = api;
        prevCdc = cdc;
        summary = buildSummary(now, api, cdc);
    }

    /**
     * CDC rates use the reader's own publish timestamps as the time base. The reader publishes about once a second
     * and we sample every 2 s, so dividing by our sampling interval would alternate between one and two publishes.
     * Between publishes the last rate is held; a reader that has stopped reads as zero.
     */
    private double[] cdcRates(long now, CdcRow cdc) {
        if (cdc == null || now - cdc.updatedAt().toEpochMilli() > CDC_STALE_MS / 2) return new double[] {0, 0, 0};
        if (prevCdc == null) return cdcRates;
        double dt = (cdc.updatedAt().toEpochMilli() - prevCdc.updatedAt().toEpochMilli()) / 1000.0;
        if (dt < 0.05) return cdcRates;   // no new publish since the last sample
        return new double[] {Math.max(0, cdc.totalEvents() - prevCdc.totalEvents()) / dt,
            Math.max(0, cdc.totalUpserts() - prevCdc.totalUpserts()) / dt,
            Math.max(0, cdc.totalDeletes() - prevCdc.totalDeletes()) / dt};
    }

    private void refreshTables(long now) {
        Map<String, Long> gg = repo.gridGainRows();
        Map<String, SybaseCount> sy = Map.of();
        try {
            sy = repo.sybaseRows();
        } catch (RuntimeException e) {
            log.debug("cdc_table_counts not available yet");
        }
        List<TableSize> out = new ArrayList<>();
        sybaseCountsAt = null;
        for (Map.Entry<String, Long> e : gg.entrySet()) {
            SybaseCount c = sy.get(e.getKey());
            out.add(new TableSize(e.getKey(), e.getValue(), c == null ? null : c.rows(), c == null ? null : e.getValue() - c.rows()));
            if (c != null && (sybaseCountsAt == null || c.countedAt().isAfter(sybaseCountsAt))) sybaseCountsAt = c.countedAt();
        }
        tables = out;
        try {
            List<String> nodes = repo.serverNodes();
            cluster = new Cluster(nodes.size(), nodes);
        } catch (RuntimeException e) {
            log.debug("sys.nodes not readable: {}", e.getMessage());
        }
        lastTablesMs = now;
    }

    private Summary buildSummary(long now, RequestMetrics.Snapshot api, CdcRow cdc) {
        long totalGg = tables.stream().mapToLong(TableSize::gridgainRows).sum();
        Long totalSy = tables.stream().allMatch(t -> t.sybaseRows() != null) && !tables.isEmpty()
            ? tables.stream().mapToLong(TableSize::sybaseRows).sum() : null;
        CdcStatus cs = cdc == null ? null : new CdcStatus(now - cdc.updatedAt().toEpochMilli() < CDC_STALE_MS, cdc.updatedAt(),
            cdc.startedAt(), cdc.lastChangeId(), cdc.pendingEvents(), cdc.totalEvents(), cdc.totalUpserts(), cdc.totalDeletes(),
            cdc.batches(), cdc.lastBatchEvents(), cdc.lastBatchMs(), cdc.lastLatencyMs(), sybaseCountsAt);
        ApiStats as = new ApiStats(api.uptimeSeconds(), api.requests(), api.reads(), api.writes(), api.clientErrors(),
            api.serverErrors(), api.requests() == 0 ? 0 : api.nanos() / 1e6 / api.requests());
        return new Summary(Instant.ofEpochMilli(now), cluster, tables, totalGg, totalSy, cs, as);
    }

    /** Latest sampled summary; before the first sample (just after start-up) an empty one, never null. */
    public Summary summary() {
        Summary s = summary;
        return s != null ? s : buildSummary(System.currentTimeMillis(), metrics.snapshot(), null);
    }

    public Series series(long since) {
        List<Sample> out = new ArrayList<>();
        synchronized (samples) {
            for (Sample s : samples) if (s.t() > since) out.add(s);
        }
        return new Series(INTERVAL_MS, System.currentTimeMillis(), out);
    }
}
