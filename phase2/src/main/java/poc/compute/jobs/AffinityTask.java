package poc.compute.jobs;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;

/** Runs a single job on the node that owns the customer's partition (data colocation by customer_id). */
abstract class AffinityTask<A> extends ComputeTaskAdapter<A, Map<String, Object>> {
    @IgniteInstanceResource
    private transient Ignite ignite;

    abstract int customerId(A arg);

    abstract ComputeJob job(A arg);

    @Override
    public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, A arg) {
        ClusterNode owner = ignite.affinity("customer").mapKeyToNode(customerId(arg));
        return Collections.singletonMap(job(arg), owner);
    }

    @Override
    public Map<String, Object> reduce(List<ComputeJobResult> results) {
        ComputeJobResult r = results.get(0);
        if (r.getException() != null) throw r.getException();
        return r.getData();
    }

    /** Job base with the Ignite instance injected on the executing node. */
    abstract static class CustomerJob extends ComputeJobAdapter {
        @IgniteInstanceResource
        protected transient Ignite ignite;
    }

    static java.util.LinkedHashMap<String, Object> fail(int rc, int errorNo, String message) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("rc", rc);
        m.put("error_no", errorNo);
        m.put("error", message);
        return m;
    }
}
