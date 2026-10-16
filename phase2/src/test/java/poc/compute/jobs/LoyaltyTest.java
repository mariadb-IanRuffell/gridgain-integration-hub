package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoyaltyTest {
    @Test
    void minusDaysSubtractsWholeDays() {
        Timestamp now = Timestamp.valueOf("2026-10-15 12:00:00");
        assertEquals(Timestamp.valueOf("2025-10-15 12:00:00"), Loyalty.minusDays(now, 365));
        assertEquals(now, Loyalty.minusDays(now, 0));
        assertEquals(now.getTime() - 86_400_000L, Loyalty.minusDays(now, 1).getTime());
    }

    @Test
    void minusDaysIsExactEvenAcrossLeapDays() {
        Timestamp t = Timestamp.valueOf("2024-03-01 00:00:00");
        assertEquals(t.getTime() - 366 * 86_400_000L, Loyalty.minusDays(t, 366).getTime());
    }

    @Test
    void listKeepsOrderAndAllowsNulls() {
        List<Object> l = Loyalty.list(1, "a", null);
        assertEquals(3, l.size());
        assertEquals(1, l.get(0));
        assertNull(l.get(2));
        l.add("mutable");
        assertEquals(4, l.size());
        assertTrue(Loyalty.list().isEmpty());
    }
}
