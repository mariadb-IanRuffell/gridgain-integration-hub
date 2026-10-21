package loadtest;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * One test definition: the data set, the operation mix, the load shape and the guardrails that protect the cluster.
 * Read from a profile file; any key can be overridden on the command line (key=value). Values are clamped to safe ranges.
 */
final class Profile {
    // data and mix
    final long rows;
    final int[] mix;               // read, update, insert, range, tx (relative weights)
    final double keySkew;          // 1 = uniform, > 1 = hot keys
    final long maxInsertRows;      // inserts stop growing the table beyond this; they become reads
    // shape
    final int minThreads, peakThreads, maxThreads;
    final int rampUpSec, holdSec, rampDownSec, cycles, thinkMs;
    // guardrails
    final int windowSec, breachWindows, recoverWindows, abortWindows;
    final double sloP95Ms, sloErrorPct, abortErrorPct;
    final int queryTimeoutSec, nodesExpected;
    final String jdbc;
    final String name;

    private final Properties raw;

    static final String[] OPS = {"read", "update", "insert", "range", "tx"};

    Profile(String name, Properties p) {
        this.name = name;
        this.raw = p;
        rows = Math.max(1000, l("rows", 100_000));
        mix = new int[] {i("mix_read", 60), i("mix_update", 15), i("mix_insert", 5), i("mix_range", 10), i("mix_tx", 10)};
        int sum = 0;
        for (int w : mix) sum += Math.max(0, w);
        if (sum <= 0) throw new IllegalArgumentException("at least one mix_* weight must be positive");
        keySkew = Math.max(1.0, d("key_skew", 2.0));
        maxInsertRows = Math.max(0, l("max_insert_rows", 50_000));

        maxThreads = Math.max(1, Math.min(i("max_threads", 64), 256));                // hard cap, whatever the profile asks for
        peakThreads = Math.max(1, Math.min(i("peak_threads", 32), maxThreads));
        minThreads = Math.max(0, Math.min(i("min_threads", 1), peakThreads));
        rampUpSec = Math.max(0, i("ramp_up_sec", 120));
        holdSec = Math.max(0, i("hold_sec", 60));
        rampDownSec = Math.max(0, i("ramp_down_sec", 120));
        cycles = Math.max(1, Math.min(i("cycles", 1), 20));
        thinkMs = Math.max(0, i("think_ms", 0));

        windowSec = Math.max(1, i("window_sec", 5));
        breachWindows = Math.max(1, i("breach_windows", 2));
        recoverWindows = Math.max(1, i("recover_windows", 3));
        abortWindows = Math.max(1, i("abort_windows", 3));
        sloP95Ms = Math.max(1, d("slo_p95_ms", 250));
        sloErrorPct = Math.max(0, d("slo_error_pct", 1.0));
        abortErrorPct = Math.max(sloErrorPct, d("abort_error_pct", 20));
        queryTimeoutSec = Math.max(1, i("query_timeout_sec", 5));
        nodesExpected = Math.max(0, i("nodes_expected", 3));
        String env = System.getenv("GG_JDBC");
        jdbc = p.getProperty("jdbc", env != null && !env.isBlank() ? env : "jdbc:ignite:thin://127.0.0.1:10800,127.0.0.1:10801,127.0.0.1:10802");
    }

    static Profile load(Path file, Map<String, String> overrides) throws IOException {
        Properties p = new Properties();
        if (file != null) try (Reader r = Files.newBufferedReader(file)) { p.load(r); }
        overrides.forEach(p::setProperty);
        String n = file == null ? "custom" : file.getFileName().toString().replaceFirst("\\.properties$", "");
        return new Profile(n, p);
    }

    private int i(String k, int d) { return (int) Math.min(Integer.MAX_VALUE, l(k, d)); }

    private long l(String k, long d) {
        String v = raw.getProperty(k);
        return v == null || v.isBlank() ? d : Long.parseLong(v.trim());
    }

    private double d(String k, double d) {
        String v = raw.getProperty(k);
        return v == null || v.isBlank() ? d : Double.parseDouble(v.trim());
    }

    Shape shape() {
        return new Shape(minThreads, peakThreads, rampUpSec, holdSec, rampDownSec, cycles);
    }

    Governor governor() {
        return new Governor(minThreads, peakThreads, sloP95Ms, sloErrorPct, abortErrorPct, breachWindows, recoverWindows, abortWindows);
    }
}
