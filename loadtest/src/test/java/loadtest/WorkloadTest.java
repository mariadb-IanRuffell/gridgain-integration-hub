package loadtest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import loadtest.Workload.Op;
import org.junit.jupiter.api.Test;

class WorkloadTest {
    final Workload w = new Workload(new int[] {60, 15, 5, 10, 10}, 100_000, 2.0);

    @Test
    void rollsMapToOperationsByWeight() {
        assertEquals(Op.READ, w.pick(0));
        assertEquals(Op.READ, w.pick(59));
        assertEquals(Op.UPDATE, w.pick(60));
        assertEquals(Op.UPDATE, w.pick(74));
        assertEquals(Op.INSERT, w.pick(75));
        assertEquals(Op.RANGE, w.pick(80));
        assertEquals(Op.TX, w.pick(90));
        assertEquals(Op.TX, w.pick(99));
    }

    @Test
    void theMixMatchesTheWeights() {
        Random rnd = new Random(1);
        Map<Op, Integer> n = new EnumMap<>(Op.class);
        for (int i = 0; i < 100_000; i++) n.merge(w.pick(rnd), 1, Integer::sum);
        assertEquals(60_000, n.get(Op.READ), 1500);
        assertEquals(15_000, n.get(Op.UPDATE), 1000);
        assertEquals(5_000, n.get(Op.INSERT), 700);
        assertEquals(10_000, n.get(Op.RANGE), 900);
        assertEquals(10_000, n.get(Op.TX), 900);
    }

    @Test
    void zeroWeightOperationsAreNeverPicked() {
        Workload readOnly = new Workload(new int[] {1, 0, 0, 0, 0}, 1000, 1.0);
        Random rnd = new Random(2);
        for (int i = 0; i < 1000; i++) assertEquals(Op.READ, readOnly.pick(rnd));
    }

    @Test
    void keysStayInTheSeededRange() {
        Random rnd = new Random(3);
        for (int i = 0; i < 100_000; i++) {
            long k = w.key(rnd);
            assertTrue(k >= 1 && k <= 100_000, "key " + k);
        }
    }

    @Test
    void skewConcentratesOnHotKeys() {
        Random rnd = new Random(4);
        int hotUniform = 0, hotSkewed = 0;
        Workload uniform = new Workload(new int[] {1, 0, 0, 0, 0}, 100_000, 1.0);
        for (int i = 0; i < 50_000; i++) {
            if (uniform.key(rnd) <= 10_000) hotUniform++;
            if (w.key(rnd) <= 10_000) hotSkewed++;
        }
        assertEquals(5_000, hotUniform, 500);              // 10% of the keys get 10% of the traffic
        assertEquals(15_800, hotSkewed, 800);              // with skew 2, sqrt(0.1) = 31.6% of the traffic
    }

    @Test
    void customersAreWithinTheirRange() {
        Random rnd = new Random(5);
        for (int i = 0; i < 10_000; i++) {
            int c = w.customer(rnd);
            assertTrue(c >= 1 && c <= 10_000);
        }
    }

    @Test
    void insertedIdsAreAboveTheSeedAndUnique() {
        long a = w.newId(7, 0, 1), b = w.newId(7, 0, 2), c = w.newId(7, 1, 1), d = w.newId(8, 0, 1);
        assertTrue(a > 100_000);
        assertEquals(4, java.util.Set.of(a, b, c, d).size());
        assertEquals(a + 1, b);
    }
}
