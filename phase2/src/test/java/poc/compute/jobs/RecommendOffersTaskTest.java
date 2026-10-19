package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ignite.IgniteException;
import org.apache.ignite.compute.ComputeJobResult;
import org.junit.jupiter.api.Test;

class RecommendOffersTaskTest {
    /** The task without a cluster: records the stale-offer purge instead of running it. */
    static class NoDb extends RecommendOffersTask {
        final java.util.List<Long> purged = new java.util.ArrayList<>();
        @Override void purgeStale(long runStartMs) { purged.add(runStartMs); }
    }

    private static ComputeJobResult node(long customers, long withOffers, long offers, long discountSum, double scoreSum, Map<String, Long> cats) {
        Map<String, Object> m = new TreeMap<>();
        m.put("customers", customers);
        m.put("customers_with_offers", withOffers);
        m.put("offers", offers);
        m.put("discount_sum", discountSum);
        m.put("score_sum", scoreSum);
        m.put("by_category", cats);
        ComputeJobResult r = mock(ComputeJobResult.class);
        doReturn(m).when(r).getData();
        return r;
    }

    @Test
    void reduceCombinesTheNodeSummaries() {
        Map<String, Object> out = new NoDb().reduce(List.of(
            node(80, 70, 200, 1400, 90.0, Map.of("HOTEL", 120L, "LOUNGE", 80L)),
            node(60, 50, 100, 600, 30.0, Map.of("HOTEL", 40L, "INSURANCE", 60L))));
        assertEquals(0, out.get("rc"));
        assertEquals(140L, out.get("customers"));
        assertEquals(120L, out.get("customers_with_offers"));
        assertEquals(300L, out.get("offers"));
        assertEquals(6.67, (Double) out.get("avg_discount_pct"), 1e-9);      // 2000 / 300
        assertEquals(0.4, (Double) out.get("avg_score"), 1e-9);              // 120 / 300
        List<?> cats = (List<?>) out.get("by_category");
        assertEquals(List.of("HOTEL", "INSURANCE", "LOUNGE"), cats.stream().map(c -> ((Map<?, ?>) c).get("category")).collect(java.util.stream.Collectors.toList()));
        assertEquals(160L, ((Map<?, ?>) cats.get(0)).get("offers"));
    }

    @Test
    void noOffersMeansZeroAverages() {
        Map<String, Object> out = new NoDb().reduce(List.of(node(5, 0, 0, 0, 0.0, Map.of())));
        assertEquals(0.0, out.get("avg_discount_pct"));
        assertEquals(0.0, out.get("avg_score"));
        assertTrue(((List<?>) out.get("by_category")).isEmpty());
    }

    @Test
    void anUnknownSingleCustomerIsReportedWithTheStoredProcedureErrorNumber() throws Exception {
        NoDb task = new NoDb();
        Field f = RecommendOffersTask.class.getDeclaredField("singleCustomer");
        f.setAccessible(true);
        f.set(task, 999);
        Map<String, Object> out = task.reduce(List.of(node(0, 0, 0, 0, 0.0, Map.of())));
        assertEquals(-1, out.get("rc"));
        assertEquals(20002, out.get("error_no"));
    }

    @Test
    void aFailedNodeFailsTheRun() {
        ComputeJobResult bad = mock(ComputeJobResult.class);
        when(bad.getException()).thenReturn(new IgniteException("node down"));
        assertThrows(IgniteException.class, () -> new NoDb().reduce(List.of(bad)));
    }

    @Test
    void theTableDefinitionIsColocatedOnCustomerId() {
        assertTrue(RecommendOffersTask.DDL.contains("affinity_key=customer_id"));
        assertTrue(RecommendOffersTask.DDL.contains("PRIMARY KEY (customer_id, product_id)"));
        assertTrue(RecommendOffersTask.DDL.contains("CREATE TABLE IF NOT EXISTS customer_offer"));
    }

    @Test
    void aFullRunPurgesOffersThatWereNotRegenerated() throws Exception {
        NoDb task = new NoDb();
        Field f = RecommendOffersTask.class.getDeclaredField("asOf");
        f.setAccessible(true);
        f.set(task, 1234L);
        task.reduce(List.of(node(5, 5, 15, 100, 5.0, Map.of("HOTEL", 15L))));
        assertEquals(List.of(1234L), task.purged);
    }

    @Test
    void aSingleCustomerRunLeavesOtherOffersAlone() throws Exception {
        NoDb task = new NoDb();
        Field f = RecommendOffersTask.class.getDeclaredField("singleCustomer");
        f.setAccessible(true);
        f.set(task, 72);
        task.reduce(List.of(node(1, 1, 3, 24, 3.0, Map.of("HOTEL", 3L))));
        assertTrue(task.purged.isEmpty());
    }
}
