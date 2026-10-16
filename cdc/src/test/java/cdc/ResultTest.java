package cdc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResultTest {
    @Test
    void recordAccessors() {
        Result r = new Result(5, 3, 2, 99L, 1234L, 17L);
        assertEquals(5, r.events());
        assertEquals(3, r.upserts());
        assertEquals(2, r.deletes());
        assertEquals(99L, r.lastChangeId());
        assertEquals(1234L, r.oldestChangeMs());
        assertEquals(17L, r.durationMs());
        assertEquals(r, new Result(5, 3, 2, 99L, 1234L, 17L));
    }
}
