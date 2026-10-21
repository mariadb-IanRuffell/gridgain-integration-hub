package loadtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ProfileTest {
    static Profile of(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return new Profile("t", p);
    }

    @Test
    void defaults() {
        Profile p = of();
        assertEquals(100_000, p.rows);
        assertArrayEquals(new int[] {60, 15, 5, 10, 10}, p.mix);
        assertEquals(32, p.peakThreads);
        assertEquals(250, p.sloP95Ms);
        assertEquals(3, p.nodesExpected);
        assertTrue(p.jdbc.startsWith("jdbc:ignite:thin://"));
    }

    @Test
    void theThreadCountIsCappedNoMatterWhatTheProfileAsksFor() {
        Profile p = of("peak_threads", "5000", "max_threads", "10000");
        assertEquals(256, p.maxThreads);
        assertEquals(256, p.peakThreads);
        Profile q = of("peak_threads", "100", "max_threads", "48");
        assertEquals(48, q.peakThreads);
    }

    @Test
    void valuesAreClampedToSafeRanges() {
        Profile p = of("rows", "5", "min_threads", "50", "peak_threads", "8", "cycles", "999", "window_sec", "0", "key_skew", "0.2", "slo_error_pct", "5", "abort_error_pct", "1");
        assertEquals(1000, p.rows);
        assertEquals(8, p.minThreads);                     // min never above peak
        assertEquals(20, p.cycles);
        assertEquals(1, p.windowSec);
        assertEquals(1.0, p.keySkew);
        assertEquals(5.0, p.abortErrorPct);                // abort threshold never below the SLO threshold
    }

    @Test
    void aMixWithNoWeightIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> of("mix_read", "0", "mix_update", "0", "mix_insert", "0", "mix_range", "0", "mix_tx", "0"));
    }

    @Test
    void fileValuesAreOverriddenByCommandLineValues(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path f = dir.resolve("mine.properties");
        Files.writeString(f, "peak_threads=12\nhold_sec=7\n# comment\nrows=20000\n");
        Profile p = Profile.load(f, Map.of("peak_threads", "9"));
        assertEquals("mine", p.name);
        assertEquals(9, p.peakThreads);
        assertEquals(7, p.holdSec);
        assertEquals(20000, p.rows);
    }

    @Test
    void shapeAndGovernorUseTheProfile() {
        Profile p = of("peak_threads", "10", "min_threads", "2", "ramp_up_sec", "10", "hold_sec", "5", "ramp_down_sec", "10", "cycles", "2");
        assertEquals(50, p.shape().totalSec());
        assertEquals(10, p.shape().threadsAt(10));
        assertEquals(10, p.governor().ceiling());
    }

    @Test
    void theShippedProfilesAreValid() throws IOException {
        try (var files = Files.list(Path.of("profiles"))) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Profile p = Profile.load(f, Map.of());
                assertTrue(p.peakThreads <= p.maxThreads, f.toString());
                assertTrue(p.shape().totalSec() > 0, f.toString());
                assertTrue(p.nodesExpected >= 1, f + ": the node-loss guardrail must stay on");
                assertTrue(p.peakThreads <= 64, f + ": shipped profiles must stay within the safe cap");
            }
        }
    }
}
