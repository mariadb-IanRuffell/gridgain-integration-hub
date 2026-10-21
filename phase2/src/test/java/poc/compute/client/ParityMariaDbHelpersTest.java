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
class ParityMariaDbHelpersTest {
    @Test
    void numbersCompareWithinAHundredthRegardlessOfType() {
        assertTrue(ParityMariaDb.same(1, 1L));
        assertTrue(ParityMariaDb.same(new BigDecimal("12.50"), 12.5));
        assertTrue(ParityMariaDb.same(new BigDecimal("1200"), new BigDecimal("1200.0000")));
        assertTrue(ParityMariaDb.same(1.001, 1.0));
        assertFalse(ParityMariaDb.same(1.02, 1.0));
        assertFalse(ParityMariaDb.same(5, 6));
    }

    @Test
    void timestampsWithinTenMillisAreTheSame() {
        assertTrue(ParityMariaDb.same(new Timestamp(1000), new Timestamp(1010)));
        assertFalse(ParityMariaDb.same(new Timestamp(1000), new Timestamp(1011)));
    }

    @Test
    void stringsIgnoreTrailingPaddingOnly() {
        assertTrue(ParityMariaDb.same("GOLD   ", "GOLD"));
        assertFalse(ParityMariaDb.same("  GOLD", "GOLD"));
        assertFalse(ParityMariaDb.same("GOLD", "gold"));
    }

    @Test
    void nullsOnlyMatchNulls() {
        assertTrue(ParityMariaDb.same(null, null));
        assertFalse(ParityMariaDb.same(null, "x"));
        assertFalse(ParityMariaDb.same(0, null));
    }

    @Test
    void otherTypesUseEquals() {
        assertTrue(ParityMariaDb.same(true, true));
        assertFalse(ParityMariaDb.same(true, false));
        assertFalse(ParityMariaDb.same("1", 1));
    }

    private static List<List<Object>> table(Object[]... rows) {
        return Arrays.stream(rows).map(r -> Arrays.asList(r)).collect(java.util.stream.Collectors.toList());
    }

    @Test
    void identicalRowsCompareClean() {
        assertNull(ParityMariaDb.compareRows(table(new Object[] {1, "a", new BigDecimal("2.50")}), table(new Object[] {1L, "a ", 2.5})));
        assertNull(ParityMariaDb.compareRows(List.of(), List.of()));
    }

    @Test
    void differencesAreDescribed() {
        assertEquals("row count 1 vs 2", ParityMariaDb.compareRows(table(new Object[] {1}), table(new Object[] {1}, new Object[] {2})));
        assertEquals("row 0 column count 2 vs 1", ParityMariaDb.compareRows(table(new Object[] {1, 2}), table(new Object[] {1})));
        String d = ParityMariaDb.compareRows(table(new Object[] {1, "a"}, new Object[] {2, "b"}), table(new Object[] {1, "a"}, new Object[] {2, "c"}));
        assertTrue(d.startsWith("row 1 col 1:"), d);
        assertTrue(d.contains("mariadb=[2, b]") && d.contains("gridgain=[2, c]"), d);
    }

    @Test
    void jobResultMapsBecomeRowsInColumnOrder() {
        Map<String, Object> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        a.put("z", 1); a.put("a", 2);
        b.put("z", 3); b.put("a", 4);
        assertEquals(List.of(List.of(1, 2), List.of(3, 4)), ParityMariaDb.rows(List.of(a, b)));
        assertTrue(ParityMariaDb.rows(List.of()).isEmpty());
    }

    @Test
    void envFallsBackToTheDefaultWhenUnsetOrBlank() {
        assertEquals("fallback", ParityMariaDb.env("SURELY_NOT_SET_" + System.nanoTime(), "fallback"));
    }
}
