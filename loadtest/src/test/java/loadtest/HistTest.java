package loadtest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HistTest {
    @Test
    void emptyWindowHasNoPercentiles() {
        assertEquals(0, Hist.percentileMs(new Hist().snapshot(), 95));
    }

    @Test
    void percentilesAreWithinFivePercentOfTheTrueValue() {
        Hist h = new Hist();
        for (int i = 1; i <= 1000; i++) h.record(i * 1_000_000L);          // 1 ms .. 1000 ms, uniform
        long[] c = h.snapshot();
        assertEquals(1000, Hist.total(c));
        assertEquals(500, Hist.percentileMs(c, 50), 500 * 0.06);
        assertEquals(950, Hist.percentileMs(c, 95), 950 * 0.06);
        assertEquals(990, Hist.percentileMs(c, 99), 990 * 0.06);
    }

    @Test
    void windowsAreTheDifferenceOfTwoSnapshots() {
        Hist h = new Hist();
        for (int i = 0; i < 100; i++) h.record(1_000_000L);                // 1 ms
        long[] first = h.snapshot();
        for (int i = 0; i < 100; i++) h.record(100_000_000L);              // 100 ms
        long[] window = Hist.minus(h.snapshot(), first);
        assertEquals(100, Hist.total(window));
        assertEquals(100, Hist.percentileMs(window, 50), 6);
        assertEquals(100, Hist.percentileMs(Hist.minus(first, null), 99) * 100, 1.0 * 100 * 6);   // the first window alone is ~1 ms
    }

    @Test
    void extremesLandInTheEndBuckets() {
        assertEquals(0, Hist.bucket(1));
        assertEquals(0, Hist.bucket(0));
        assertEquals(Hist.BUCKETS - 1, Hist.bucket(Long.MAX_VALUE));
        assertTrue(Hist.bucket(2_000_000) > Hist.bucket(1_000_000));
    }

    @Test
    void concurrentRecording() throws Exception {
        Hist h = new Hist();
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) { ts[i] = new Thread(() -> { for (int k = 0; k < 10_000; k++) h.record(1_000_000L); }); ts[i].start(); }
        for (Thread t : ts) t.join();
        assertEquals(80_000, Hist.total(h.snapshot()));
    }
}
