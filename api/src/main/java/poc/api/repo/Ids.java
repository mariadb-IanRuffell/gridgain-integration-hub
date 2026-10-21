package poc.api.repo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ignite.client.ClientAtomicSequence;
import org.apache.ignite.client.IgniteClient;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide id generators (GridGain atomic sequences). Ids start at 1,000,000,000, far above the MariaDB
 * identity values that CDC replicates, so rows created here can never collide with replicated rows.
 * Order ids are NOT generated here: orders are created by the place-order compute job, which owns its own sequences.
 */
@Component
public class Ids {
    private static final long BASE = 1_000_000_000L;

    private final IgniteClient client;
    private final Map<String, ClientAtomicSequence> sequences = new ConcurrentHashMap<>();

    Ids(IgniteClient client) {
        this.client = client;
    }

    public int next(String name) {
        return (int) sequences.computeIfAbsent("api_seq_" + name, n -> client.atomicSequence(n, BASE, true)).incrementAndGet();
    }
}
