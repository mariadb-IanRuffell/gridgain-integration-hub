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
import poc.api.repo.DashboardRepository.SourceCount;
import poc.api.repo.DashboardRepository.TableRef;

/**
 * Samples REST counters (in-process) and the CDC counters each reader publishes into GridGain (Sybase and Oracle) every 2 seconds,
 * turns the cumulative values into per-second rates, and keeps the last 15 minutes in memory for the graphs.
 */
@Service
public class DashboardService {
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    static final int INTERVAL_MS = 2_000, KEEP = 450;           // 450 x 2 s = 15 minutes
    private static final long CDC_STALE_MS = 10_000;            // the readers heartbeat every 2 s

    /** cdc* fields are the Sybase reader, ora* fields the Oracle reader. */
    public record Sample(long t, double apiReqPerSec, double apiReadsPerSec, double apiWritesPerSec, double apiErrorsPerSec, double apiAvgMs,
                         double cdcEventsPerSec, double cdcUpsertsPerSec, double cdcDeletesPerSec, long cdcPending, long cdcLatencyMs,
                         double oraEventsPerSec, double oraUpsertsPerSec, double oraDeletesPerSec, long oraPending, long oraLatencyMs) {}

    public record Series(int intervalMs, long serverTime, List<Sample> samples) {}

    public record TableSize(String name, String source, long gridgainRows, Long sourceRows, Long difference) {}

    public record Cluster(int nodes, List<String> nodeIds) {}

    public record CdcStatus(String source, boolean running, Instant lastUpdate, Instant startedAt, long position, long pendingEvents,
                            long totalEvents, long totalUpserts, long totalDeletes, long batches, int lastBatchEvents,
                            long lastBatchMs, long lastLatencyMs) {}

    public record ApiStats(long uptimeSeconds, long requests, long reads, long writes, long clientErrors, long serverErrors, double avgMs) {}

    public record Summary(Instant generatedAt, Cluster cluster, List<TableSize> tables, long totalGridgainRows, Long totalSourceRows,
                          Instant sourceCountsAt, List<CdcStatus> cdc, ApiStats api) {}

    /** Per-reader state: the previous published row and the last rates (held between publishes). */
    private static final class Track {
        final String consumer, source;
        CdcRow prev, cur;
        double[] rates = {0, 0, 0};   // events, upserts, deletes per second

        Track(String consumer, String source) {
            this.consumer = consumer;
            this.source = source;
        }

        /**
         * Rates use the reader's own publish timestamps as the time base: it publishes about once a second and we sample every 2 s,
         * so dividing by our sampling interval would alternate between one and two publishes. Between publishes the last rate is held;
         * a reader that has stopped reads as zero.
         */
        void update(long now, CdcRow row) {
            cur = row;
            if (row == null || now - row.updatedAt().toEpochMilli() > CDC_STALE_MS / 2) {
                rates = new double[] {0, 0, 0};
            } else if (prev != null) {
                double dt = (row.updatedAt().toEpochMilli() - prev.updatedAt().toEpochMilli()) / 1000.0;
                if (dt >= 0.05) {
                    rates = new double[] {Math.max(0, row.totalEvents() - prev.totalEvents()) / dt,
                        Math.max(0, row.totalUpserts() - prev.totalUpserts()) / dt, Math.max(0, row.totalDeletes() - prev.totalDeletes()) / dt};
                }
            }
            prev = row;
        }

        CdcStatus status(long now) {
            CdcRow c = cur;
            return c == null ? null : new CdcStatus(source, now - c.updatedAt().toEpochMilli() < CDC_STALE_MS, c.updatedAt(), c.startedAt(),
                c.lastChangeId(), c.pendingEvents(), c.totalEvents(), c.totalUpserts(), c.totalDeletes(), c.batches(), c.lastBatchEvents(),
                c.lastBatchMs(), c.lastLatencyMs());
        }
    }

    private final RequestMetrics metrics;
    private final DashboardRepository repo;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Track sybase = new Track(DashboardRepository.SYBASE_CONSUMER, "sybase");
    private final Track oracle = new Track(DashboardRepository.ORACLE_CONSUMER, "oracle");

    private long prevT;
    private RequestMetrics.Snapshot prevApi;
    private volatile Summary summary;
    private long lastTablesMs;
    private List<TableSize> tables = List.of();
    private Instant sourceCountsAt;
    private Cluster cluster = new Cluster(0, List.of());

    DashboardService(RequestMetrics metrics, DashboardRepository repo) {
        this.metrics = metrics;
        this.repo = repo;
    }

    @Scheduled(fixedRate = INTERVAL_MS, initialDelay = 3_000)
    void sample() {
        long now = System.currentTimeMillis();
        RequestMetrics.Snapshot api = metrics.snapshot();
        try {
            sybase.update(now, repo.cdcStatus(sybase.consumer).orElse(null));
            oracle.update(now, repo.cdcStatus(oracle.consumer).orElse(null));
            if (now - lastTablesMs >= 1_500) refreshTables(now);
        } catch (RuntimeException e) {
            log.debug("dashboard sampling: GridGain not readable: {}", e.getMessage());
        }

        if (prevApi != null) {
            double dt = Math.max((now - prevT) / 1000.0, 0.001);
            long dReq = api.requests() - prevApi.requests();
            double avgMs = dReq > 0 ? (api.nanos() - prevApi.nanos()) / 1e6 / dReq : 0;
            double errors = (api.clientErrors() + api.serverErrors() - prevApi.clientErrors() - prevApi.serverErrors()) / dt;
            Sample s = new Sample(now, dReq / dt, (api.reads() - prevApi.reads()) / dt, (api.writes() - prevApi.writes()) / dt, errors, avgMs,
                sybase.rates[0], sybase.rates[1], sybase.rates[2], pending(sybase), latency(sybase),
                oracle.rates[0], oracle.rates[1], oracle.rates[2], pending(oracle), latency(oracle));
            synchronized (samples) {
                samples.addLast(s);
                while (samples.size() > KEEP) samples.removeFirst();
            }
        }
        prevT = now;
        prevApi = api;
        summary = buildSummary(now, api);
    }

    private static long pending(Track t) {
        return t.cur == null ? 0 : t.cur.pendingEvents();
    }

    private static long latency(Track t) {
        return t.cur == null ? 0 : t.cur.lastLatencyMs();
    }

    private void refreshTables(long now) {
        Map<String, Long> gg = repo.gridGainRows();
        Map<String, SourceCount> src = Map.of();
        try {
            src = repo.sourceRows();
        } catch (RuntimeException e) {
            log.debug("cdc_table_counts not available yet");
        }
        List<TableSize> out = new ArrayList<>();
        sourceCountsAt = null;
        for (TableRef t : DashboardRepository.TABLES) {
            long g = gg.get(t.name());
            SourceCount c = src.get(t.name());
            out.add(new TableSize(t.name(), t.source(), g, c == null ? null : c.rows(), c == null ? null : g - c.rows()));
            if (c != null && (sourceCountsAt == null || c.countedAt().isAfter(sourceCountsAt))) sourceCountsAt = c.countedAt();
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

    private Summary buildSummary(long now, RequestMetrics.Snapshot api) {
        long totalGg = tables.stream().mapToLong(TableSize::gridgainRows).sum();
        Long totalSrc = !tables.isEmpty() && tables.stream().allMatch(t -> t.sourceRows() != null)
            ? tables.stream().mapToLong(TableSize::sourceRows).sum() : null;
        List<CdcStatus> cdc = new ArrayList<>();
        for (Track t : List.of(sybase, oracle)) {
            CdcStatus s = t.status(now);
            if (s != null) cdc.add(s);
        }
        ApiStats as = new ApiStats(api.uptimeSeconds(), api.requests(), api.reads(), api.writes(), api.clientErrors(), api.serverErrors(),
            api.requests() == 0 ? 0 : api.nanos() / 1e6 / api.requests());
        return new Summary(Instant.ofEpochMilli(now), cluster, tables, totalGg, totalSrc, sourceCountsAt, cdc, as);
    }

    /** Latest sampled summary; before the first sample (just after start-up) an empty one, never null. */
    public Summary summary() {
        Summary s = summary;
        return s != null ? s : buildSummary(System.currentTimeMillis(), metrics.snapshot());
    }

    public Series series(long since) {
        List<Sample> out = new ArrayList<>();
        synchronized (samples) {
            for (Sample s : samples) if (s.t() > since) out.add(s);
        }
        return new Series(INTERVAL_MS, System.currentTimeMillis(), out);
    }
}
