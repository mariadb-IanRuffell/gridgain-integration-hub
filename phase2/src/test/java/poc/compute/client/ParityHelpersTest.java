package poc.compute.client;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The comparison rules the parity harness uses to decide that a compute job and a stored procedure agree. */
class ParityHelpersTest {
    @Test
    void numbersCompareWithinAHundredthRegardlessOfType() {
        assertTrue(Parity.same(1, 1L));
        assertTrue(Parity.same(new BigDecimal("12.50"), 12.5));
        assertTrue(Parity.same(new BigDecimal("1200"), new BigDecimal("1200.0000")));
        assertTrue(Parity.same(1.001, 1.0));
        assertFalse(Parity.same(1.02, 1.0));
        assertFalse(Parity.same(5, 6));
    }

    @Test
    void timestampsWithinTenMillisAreTheSame() {
        assertTrue(Parity.same(new Timestamp(1000), new Timestamp(1010)));
        assertFalse(Parity.same(new Timestamp(1000), new Timestamp(1011)));
    }

    @Test
    void stringsIgnoreTrailingPaddingOnly() {
        assertTrue(Parity.same("GOLD   ", "GOLD"));
        assertFalse(Parity.same("  GOLD", "GOLD"));
        assertFalse(Parity.same("GOLD", "gold"));
    }

    @Test
    void nullsOnlyMatchNulls() {
        assertTrue(Parity.same(null, null));
        assertFalse(Parity.same(null, "x"));
        assertFalse(Parity.same(0, null));
    }

    @Test
    void otherTypesUseEquals() {
        assertTrue(Parity.same(true, true));
        assertFalse(Parity.same(true, false));
        assertFalse(Parity.same("1", 1));
    }

    private static List<List<Object>> table(Object[]... rows) {
        return Arrays.stream(rows).map(r -> Arrays.asList(r)).collect(java.util.stream.Collectors.toList());
    }

    @Test
    void identicalRowsCompareClean() {
        assertNull(Parity.compareRows(table(new Object[] {1, "a", new BigDecimal("2.50")}), table(new Object[] {1L, "a ", 2.5})));
        assertNull(Parity.compareRows(List.of(), List.of()));
    }

    @Test
    void differencesAreDescribed() {
        assertEquals("row count 1 vs 2", Parity.compareRows(table(new Object[] {1}), table(new Object[] {1}, new Object[] {2})));
        assertEquals("row 0 column count 2 vs 1", Parity.compareRows(table(new Object[] {1, 2}), table(new Object[] {1})));
        String d = Parity.compareRows(table(new Object[] {1, "a"}, new Object[] {2, "b"}), table(new Object[] {1, "a"}, new Object[] {2, "c"}));
        assertTrue(d.startsWith("row 1 col 1:"), d);
        assertTrue(d.contains("sybase=[2, b]") && d.contains("gridgain=[2, c]"), d);
    }

    @Test
    void jobResultMapsBecomeRowsInColumnOrder() {
        Map<String, Object> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        a.put("z", 1); a.put("a", 2);
        b.put("z", 3); b.put("a", 4);
        assertEquals(List.of(List.of(1, 2), List.of(3, 4)), Parity.rows(List.of(a, b)));
        assertTrue(Parity.rows(List.of()).isEmpty());
    }

    @Test
    void envFallsBackToTheDefaultWhenUnsetOrBlank() {
        assertEquals("fallback", Parity.env("SURELY_NOT_SET_" + System.nanoTime(), "fallback"));
    }
}
