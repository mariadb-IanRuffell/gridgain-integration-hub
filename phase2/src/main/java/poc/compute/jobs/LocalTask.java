package poc.compute.jobs;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;

/** Runs a single job on the node that received the request (for work that is not tied to one customer's partition). */
abstract class LocalTask<A> extends ComputeTaskAdapter<A, Map<String, Object>> {
    @IgniteInstanceResource
    protected transient Ignite ignite;

    abstract ComputeJob job(A arg);

    @Override
    public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, A arg) {
        return Collections.singletonMap(job(arg), ignite.cluster().localNode());
    }

    @Override
    public Map<String, Object> reduce(List<ComputeJobResult> results) {
        ComputeJobResult r = results.get(0);
        if (r.getException() != null) throw r.getException();
        return r.getData();
    }
}
