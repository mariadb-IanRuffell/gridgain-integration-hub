package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCluster;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.compute.ComputeJobResult;
import org.junit.jupiter.api.Test;

class TaskBaseClassesTest {
    static class Echo extends ComputeJobAdapter {
        final int id;
        Echo(int id) { this.id = id; }
        @Override public Object execute() { return id; }
    }

    static class ByCustomer extends AffinityTask<Integer> {
        @Override int customerId(Integer arg) { return arg; }
        @Override ComputeJob job(Integer arg) { return new Echo(arg); }
    }

    static class Here extends LocalTask<Integer> {
        @Override ComputeJob job(Integer arg) { return new Echo(arg); }
    }

    private static void inject(Object target, Class<?> declaring, String field, Object value) throws Exception {
        Field f = declaring.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static ComputeJobResult result(Object data, IgniteException error) {
        ComputeJobResult r = mock(ComputeJobResult.class);
        doReturn(data).when(r).getData();
        when(r.getException()).thenReturn(error);
        return r;
    }

    @Test
    void affinityTaskSendsTheJobToTheNodeThatOwnsTheCustomer() throws Exception {
        Ignite ignite = mock(Ignite.class);
        @SuppressWarnings("unchecked") Affinity<Object> aff = mock(Affinity.class);
        ClusterNode owner = mock(ClusterNode.class);
        when(ignite.affinity("customer")).thenReturn(aff);
        when(aff.mapKeyToNode(72)).thenReturn(owner);
        ByCustomer task = new ByCustomer();
        inject(task, AffinityTask.class, "ignite", ignite);

        Map<? extends ComputeJob, ClusterNode> jobs = task.map(List.of(mock(ClusterNode.class), owner), 72);
        assertEquals(1, jobs.size());
        assertSame(owner, jobs.values().iterator().next());
        assertEquals(72, ((Echo) jobs.keySet().iterator().next()).id);
    }

    @Test
    void localTaskRunsOnTheLocalNode() throws Exception {
        Ignite ignite = mock(Ignite.class);
        IgniteCluster cluster = mock(IgniteCluster.class);
        ClusterNode local = mock(ClusterNode.class);
        when(ignite.cluster()).thenReturn(cluster);
        when(cluster.localNode()).thenReturn(local);
        Here task = new Here();
        inject(task, LocalTask.class, "ignite", ignite);
        assertSame(local, task.map(List.of(mock(ClusterNode.class)), 1).values().iterator().next());
    }

    @Test
    void reduceReturnsTheSingleJobResultOrRethrowsItsFailure() {
        Map<String, Object> data = new LinkedHashMap<>(Map.of("rc", 0));
        assertSame(data, new ByCustomer().reduce(List.of(result(data, null))));
        assertSame(data, new Here().reduce(List.of(result(data, null))));
        ComputeJobResult bad = result(null, new IgniteException("boom"));
        assertThrows(IgniteException.class, () -> new ByCustomer().reduce(List.of(bad)));
        assertThrows(IgniteException.class, () -> new Here().reduce(List.of(bad)));
    }

    @Test
    void failBuildsTheStoredProcedureStyleErrorRow() {
        LinkedHashMap<String, Object> m = AffinityTask.fail(-1, 20103, "flight not found");
        assertEquals(List.of("rc", "error_no", "error"), List.copyOf(m.keySet()));
        assertEquals(-1, m.get("rc"));
        assertEquals(20103, m.get("error_no"));
        assertEquals("flight not found", m.get("error"));
    }
}
