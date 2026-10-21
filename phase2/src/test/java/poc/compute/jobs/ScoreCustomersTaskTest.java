package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJobResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoreCustomersTaskTest {
    @ParameterizedTest
    // recency, orders12m, openCases, sentiment, expected
    @CsvSource({"10,5,0,0.5,0", "90,4,0,0,0", "91,4,0,0,20", "180,2,0,0,30", "181,1,0,0,60", "365,1,0,0,60",
                "366,1,0,0,80", "9999,0,0,0,90", "9999,0,5,-1,100", "30,4,1,0,5", "30,4,2,0,10", "30,4,9,0,10",
                "30,4,0,-0.01,10", "30,4,0,0.0,0"})
    void churnScoreComponents(long recency, long orders, long open, double sentiment, int expected) {
        assertEquals(expected, ScoreCustomersTask.churnScore(recency, orders, open, sentiment));
    }

    @Test
    void scoreNeverExceedsOneHundred() {
        assertEquals(100, ScoreCustomersTask.churnScore(100_000, 0, 1_000, -1));
    }

    @ParameterizedTest
    @CsvSource({"0,LOW", "29,LOW", "30,MEDIUM", "59,MEDIUM", "60,HIGH", "100,HIGH"})
    void riskBands(int score, String band) {
        assertEquals(band, ScoreCustomersTask.riskBand(score));
    }

    @Test
    void oneJobPerNodeAndDefaultsToNow() {
        ClusterNode a = mock(ClusterNode.class), b = mock(ClusterNode.class), c = mock(ClusterNode.class);
        var jobs = new ScoreCustomersTask().map(List.of(a, b, c), 1_700_000_000_000L);
        assertEquals(3, jobs.size());
        assertEquals(new java.util.HashSet<>(List.of(a, b, c)), new java.util.HashSet<>(jobs.values()));
        assertEquals(1, new ScoreCustomersTask().map(List.of(a), null).size());
    }

    private static ComputeJobResult result(Object data) {
        ComputeJobResult r = mock(ComputeJobResult.class);
        doReturn(data).when(r).getData();
        return r;
    }

    private static Map<String, List<Long>> part(Object... bandCountSum) {
        Map<String, List<Long>> m = new TreeMap<>();
        for (int i = 0; i < bandCountSum.length; i += 3)
            m.put((String) bandCountSum[i], new ArrayList<>(Arrays.asList((Long) bandCountSum[i + 1], (Long) bandCountSum[i + 2])));
        return m;
    }

    @Test
    void reduceMergesNodeSummariesWithIntegerAverages() {
        List<Object> out = new ScoreCustomersTask().reduce(List.of(
            result(part("HIGH", 2L, 150L, "LOW", 10L, 100L)),
            result(part("HIGH", 1L, 61L, "MEDIUM", 4L, 170L))));
        assertEquals(3, out.size());
        Map<?, ?> high = (Map<?, ?>) out.get(0), low = (Map<?, ?>) out.get(1), med = (Map<?, ?>) out.get(2);
        assertEquals("HIGH", high.get("risk_band"));
        assertEquals(3L, high.get("customers"));
        assertEquals(70L, high.get("avg_score"));         // (150 + 61) / 3 = 70 (integer division, like the SQL integer average)
        assertEquals(10L, low.get("avg_score"));
        assertEquals(42L, med.get("avg_score"));          // 170 / 4
    }

    @Test
    void reduceOfNothingIsEmpty() {
        assertTrue(new ScoreCustomersTask().reduce(List.of(result(part()))).isEmpty());
    }

    @Test
    void reducePropagatesJobFailures() {
        ComputeJobResult bad = mock(ComputeJobResult.class);
        when(bad.getException()).thenReturn(new org.apache.ignite.IgniteException("node down"));
        assertThrows(org.apache.ignite.IgniteException.class, () -> new ScoreCustomersTask().reduce(List.of(bad)));
    }
}
