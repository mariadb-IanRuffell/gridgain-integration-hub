package poc.api.metrics;

import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** Cumulative counters for REST traffic. The dashboard's sampler derives rates from successive snapshots. */
@Component
public class RequestMetrics {
    private final LongAdder requests = new LongAdder(), reads = new LongAdder(), writes = new LongAdder();
    private final LongAdder clientErrors = new LongAdder(), serverErrors = new LongAdder(), nanos = new LongAdder();
    private final long startedMs = System.currentTimeMillis();

    public record Snapshot(long requests, long reads, long writes, long clientErrors, long serverErrors, long nanos,
                           long uptimeSeconds) {}

    public void record(String method, int status, long durationNanos) {
        requests.increment();
        nanos.add(durationNanos);
        if ("GET".equals(method) || "HEAD".equals(method)) reads.increment(); else writes.increment();
        if (status >= 500) serverErrors.increment(); else if (status >= 400) clientErrors.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(requests.sum(), reads.sum(), writes.sum(), clientErrors.sum(), serverErrors.sum(), nanos.sum(),
            (System.currentTimeMillis() - startedMs) / 1000);
    }
}
