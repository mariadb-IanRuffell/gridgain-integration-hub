package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ignite.compute.ComputeJobResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DisruptionRulesTest {
    // ---- ProcessDisruptionsTask: compensation written for each affected segment

    @ParameterizedTest
    @CsvSource({"true,0,CANCELLED,1", "true,900,CANCELLED,1", "false,720,DELAY_720,0.75", "false,800,DELAY_720,0.75",
                "false,719,DELAY_360,0.5", "false,360,DELAY_360,0.5", "false,359,DELAY_SHORT,0.25", "false,180,DELAY_SHORT,0.25"})
    void processDisruptionsRule(boolean cancelled, int delay, String reason, String share) {
        assertEquals(reason, ProcessDisruptionsTask.reason(cancelled, delay));
        assertEquals(0, new BigDecimal(share).compareTo(ProcessDisruptionsTask.share(cancelled, delay)));
    }

    // ---- DisruptionImpactTask: the suggested (not written) compensation: delays under 180 minutes owe nothing

    @ParameterizedTest
    @CsvSource({"true,0,1", "false,720,0.75", "false,360,0.5", "false,359,0.25", "false,180,0.25", "false,179,0", "false,0,0"})
    void impactShare(boolean cancelled, int delay, String share) {
        assertEquals(0, new BigDecimal(share).compareTo(DisruptionImpactTask.share(cancelled, delay)));
    }

    @ParameterizedTest
    @CsvSource({"PLATINUM,FIRST,95000,439", "PLATINUM,BUSINESS,0,420", "GOLD,ECONOMY,25000,312", "SILVER,ECONOMY,9999,210",
                "BLUE,ECONOMY,0,110", "BLUE,ECONOMY,500000,119", "UNKNOWN,UNKNOWN,0,110"})
    void priorityOrdersTierThenCabinThenSpend(String tier, String cabin, String spend, int expected) {
        assertEquals(expected, DisruptionImpactTask.priority(tier, cabin, new BigDecimal(spend)));
    }

    @Test
    void aHigherTierAlwaysOutranksAHigherCabinOrSpend() {
        int goldFirstBigSpender = DisruptionImpactTask.priority("GOLD", "FIRST", new BigDecimal("900000"));
        int platinumEconomyNoSpend = DisruptionImpactTask.priority("PLATINUM", "ECONOMY", BigDecimal.ZERO);
        assertTrue(platinumEconomyNoSpend > goldFirstBigSpender);
    }

    private static ComputeJobResult result(Object data) {
        ComputeJobResult r = mock(ComputeJobResult.class);
        doReturn(data).when(r).getData();
        return r;
    }

    private static Map<String, Object> passenger(int segment, int priority, String tier, String base, String suggested) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("segment_id", segment);
        m.put("priority", priority);
        m.put("loyalty_tier", tier);
        m.put("base_compensation", new BigDecimal(base));
        m.put("suggested_compensation", new BigDecimal(suggested));
        return m;
    }

    @Test
    void impactReduceRanksPassengersAndTotalsByTier() {
        List<Map<String, Object>> node1 = List.of(passenger(5, 210, "SILVER", "100.00", "125.00"), passenger(2, 410, "PLATINUM", "200.00", "400.00"));
        List<Map<String, Object>> node2 = List.of(passenger(9, 410, "PLATINUM", "50.00", "100.00"), passenger(1, 110, "BLUE", "10.00", "10.00"));
        Map<String, Object> out = new DisruptionImpactTask().reduce(List.of(result(node1), result(node2)));

        assertEquals(0, out.get("rc"));
        List<?> ranked = (List<?>) out.get("passengers");
        assertEquals(List.of(2, 9, 5, 1), ranked.stream().map(p -> ((Map<?, ?>) p).get("segment_id")).collect(java.util.stream.Collectors.toList()));   // priority desc, then segment
        assertEquals(new BigDecimal("360.00"), out.get("total_base_compensation"));
        assertEquals(new BigDecimal("635.00"), out.get("total_suggested_compensation"));
        List<?> tiers = (List<?>) out.get("by_tier");
        assertEquals(List.of("BLUE", "PLATINUM", "SILVER"), tiers.stream().map(t -> ((Map<?, ?>) t).get("loyalty_tier")).collect(java.util.stream.Collectors.toList()));
        Map<?, ?> platinum = (Map<?, ?>) tiers.get(1);
        assertEquals(2L, platinum.get("passengers"));
        assertEquals(new BigDecimal("500.00"), platinum.get("suggested_compensation"));
    }

    @Test
    void impactReduceWithNoPassengers() {
        Map<String, Object> out = new DisruptionImpactTask().reduce(List.of(result(new ArrayList<Map<String, Object>>())));
        assertTrue(((List<?>) out.get("passengers")).isEmpty());
        assertEquals(BigDecimal.ZERO, out.get("total_suggested_compensation"));
    }

    @Test
    void processDisruptionsReduceSumsPerReason() {
        Map<String, List<Object>> a = new TreeMap<>(), b = new TreeMap<>();
        a.put("CANCELLED", new ArrayList<>(Arrays.asList(2L, new BigDecimal("300.00"))));
        a.put("DELAY_360", new ArrayList<>(Arrays.asList(1L, new BigDecimal("50.00"))));
        b.put("CANCELLED", new ArrayList<>(Arrays.asList(3L, new BigDecimal("120.50"))));
        List<Object> out = new ProcessDisruptionsTask().reduce(List.of(result(a), result(b)));
        assertEquals(2, out.size());
        Map<?, ?> cancelled = (Map<?, ?>) out.get(0);
        assertEquals("CANCELLED", cancelled.get("reason"));
        assertEquals(5L, cancelled.get("segments"));
        assertEquals(new BigDecimal("420.50"), cancelled.get("total_amount"));
        assertEquals("DELAY_360", ((Map<?, ?>) out.get(1)).get("reason"));
    }

    @Test
    void processDisruptionsMapAppliesDefaultsAndOneJobPerNode() {
        var n1 = mock(org.apache.ignite.cluster.ClusterNode.class);
        var n2 = mock(org.apache.ignite.cluster.ClusterNode.class);
        assertEquals(2, new ProcessDisruptionsTask().map(List.of(n1, n2), null).size());
        assertEquals(1, new ProcessDisruptionsTask().map(List.of(n1), Map.of("min_delay", 240, "as_of", 1L)).size());
    }
}
