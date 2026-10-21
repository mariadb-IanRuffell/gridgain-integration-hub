package loadtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * OLTP load tester for the GridGain cluster: ramps the load up and back down over thin JDBC while a governor protects the cluster.
 *
 *   run.sh prepare [profile]                  create the test table and seed it (idempotent)
 *   run.sh run <profile> [key=value ...]      run a test; results go to results/<timestamp>-<profile>/
 *   run.sh cleanup                            drop the test table
 *   run.sh stop                               ask a running test to ramp down and finish (creates the STOP file)
 */
public final class LoadTest {
    static final Path STOP = Path.of("STOP");

    public static void main(String[] a) throws Exception {
        String cmd = a.length > 0 ? a[0] : "help";
        switch (cmd) {
            case "prepare" -> prepare(profile(a, 1));
            case "run" -> {
                if (a.length < 2) usage();
                System.exit(run(profile(a, 1)) ? 0 : 1);
            }
            case "cleanup" -> {
                try (Connection c = DriverManager.getConnection(Profile.load(null, Map.of()).jdbc)) {
                    Table.drop(c);
                }
                System.out.println("dropped " + Table.NAME);
            }
            case "stop" -> {
                Files.writeString(STOP, "stop requested " + LocalDateTime.now() + "\n");
                System.out.println("STOP file written: the running test will ramp down at the end of its current window");
            }
            default -> usage();
        }
    }

    static Profile profile(String[] a, int from) throws Exception {
        Path file = null;
        Map<String, String> overrides = new LinkedHashMap<>();
        for (int i = from; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq > 0) overrides.put(a[i].substring(0, eq), a[i].substring(eq + 1));
            else file = Files.exists(Path.of(a[i])) ? Path.of(a[i]) : Path.of("profiles", a[i] + ".properties");
        }
        if (file != null && !Files.exists(file)) throw new IllegalArgumentException("no such profile: " + file);
        return Profile.load(file, overrides);
    }

    static void prepare(Profile p) throws Exception {
        try (Connection c = DriverManager.getConnection(p.jdbc)) {
            long t0 = System.currentTimeMillis();
            long n = Table.prepare(c, p.rows);
            System.out.printf("%s: %d rows ready (%d inserted in %.1f s)%n", Table.NAME, p.rows, n, (System.currentTimeMillis() - t0) / 1000.0);
        }
    }

    static boolean run(Profile p) throws Exception {
        Files.deleteIfExists(STOP);
        String started = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Path dir = Path.of("results", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + p.name);
        Runner runner = new Runner(p, System.out, STOP);
        CountDownLatch finished = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {       // Ctrl-C: ramp down, clean up and write the report before exiting
            runner.requestStop();
            try { finished.await(); } catch (InterruptedException ignored) { }
        }));
        long t0 = System.nanoTime();
        Runner.Result r;
        try {
            r = runner.run();
        } finally {
            Files.deleteIfExists(STOP);
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        Report.write(dir, p, r, started, seconds);
        System.out.printf("%n%s: %,d ops, %,d errors, best %.0f ops/s at up to %d threads, %d back-offs. Report: %s%n",
            r.abortReason != null ? "ABORTED (" + r.abortReason + ")" : r.stoppedByUser ? "STOPPED" : "COMPLETED", r.totalOps, r.totalErrors, r.bestOpsPerSec,
            r.peakThreadsReached, r.backoffs, dir.resolve("report.html").toAbsolutePath());
        finished.countDown();
        return r.abortReason == null;
    }

    private static void usage() {
        System.err.println("usage: run.sh prepare [profile] | run <profile> [key=value ...] | cleanup | stop\n"
            + "profiles: " + String.join(", ", profileNames()));
        System.exit(2);
    }

    private static String[] profileNames() {
        try (var s = Files.list(Path.of("profiles"))) {
            return s.map(f -> f.getFileName().toString().replaceFirst("\\.properties$", "")).sorted().toArray(String[]::new);
        } catch (Exception e) {
            return new String[0];
        }
    }
}
