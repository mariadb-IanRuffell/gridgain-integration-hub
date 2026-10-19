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
class ParitySybaseHelpersTest {
    @Test
    void numbersCompareWithinAHundredthRegardlessOfType() {
        assertTrue(ParitySybase.same(1, 1L));
        assertTrue(ParitySybase.same(new BigDecimal("12.50"), 12.5));
        assertTrue(ParitySybase.same(new BigDecimal("1200"), new BigDecimal("1200.0000")));
        assertTrue(ParitySybase.same(1.001, 1.0));
        assertFalse(ParitySybase.same(1.02, 1.0));
        assertFalse(ParitySybase.same(5, 6));
    }

    @Test
    void timestampsWithinTenMillisAreTheSame() {
        assertTrue(ParitySybase.same(new Timestamp(1000), new Timestamp(1010)));
        assertFalse(ParitySybase.same(new Timestamp(1000), new Timestamp(1011)));
    }

    @Test
    void stringsIgnoreTrailingPaddingOnly() {
        assertTrue(ParitySybase.same("GOLD   ", "GOLD"));
        assertFalse(ParitySybase.same("  GOLD", "GOLD"));
        assertFalse(ParitySybase.same("GOLD", "gold"));
    }

    @Test
    void nullsOnlyMatchNulls() {
        assertTrue(ParitySybase.same(null, null));
        assertFalse(ParitySybase.same(null, "x"));
        assertFalse(ParitySybase.same(0, null));
    }

    @Test
    void otherTypesUseEquals() {
        assertTrue(ParitySybase.same(true, true));
        assertFalse(ParitySybase.same(true, false));
        assertFalse(ParitySybase.same("1", 1));
    }

    private static List<List<Object>> table(Object[]... rows) {
        return Arrays.stream(rows).map(r -> Arrays.asList(r)).collect(java.util.stream.Collectors.toList());
    }

    @Test
    void identicalRowsCompareClean() {
        assertNull(ParitySybase.compareRows(table(new Object[] {1, "a", new BigDecimal("2.50")}), table(new Object[] {1L, "a ", 2.5})));
        assertNull(ParitySybase.compareRows(List.of(), List.of()));
    }

    @Test
    void differencesAreDescribed() {
        assertEquals("row count 1 vs 2", ParitySybase.compareRows(table(new Object[] {1}), table(new Object[] {1}, new Object[] {2})));
        assertEquals("row 0 column count 2 vs 1", ParitySybase.compareRows(table(new Object[] {1, 2}), table(new Object[] {1})));
        String d = ParitySybase.compareRows(table(new Object[] {1, "a"}, new Object[] {2, "b"}), table(new Object[] {1, "a"}, new Object[] {2, "c"}));
        assertTrue(d.startsWith("row 1 col 1:"), d);
        assertTrue(d.contains("sybase=[2, b]") && d.contains("gridgain=[2, c]"), d);
    }

    @Test
    void jobResultMapsBecomeRowsInColumnOrder() {
        Map<String, Object> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        a.put("z", 1); a.put("a", 2);
        b.put("z", 3); b.put("a", 4);
        assertEquals(List.of(List.of(1, 2), List.of(3, 4)), ParitySybase.rows(List.of(a, b)));
        assertTrue(ParitySybase.rows(List.of()).isEmpty());
    }

    @Test
    void envFallsBackToTheDefaultWhenUnsetOrBlank() {
        assertEquals("fallback", ParitySybase.env("SURELY_NOT_SET_" + System.nanoTime(), "fallback"));
    }
}
