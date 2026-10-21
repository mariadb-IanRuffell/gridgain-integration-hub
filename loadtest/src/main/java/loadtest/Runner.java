package loadtest;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import loadtest.Workload.Op;

/**
 * Runs the OLTP workload with the planned shape and the governor in charge. One thread and one JDBC connection per worker; a worker is
 * idle (but keeps its connection) while its index is at or above the current target, so ramping up and down never churns connections.
 * The sampler (this thread) closes a window every windowSec seconds, asks the governor what to do, sets the new target and checks that
 * the cluster still has all of its server nodes: losing one while loaded is the situation this tool must not make worse, so it stops.
 */
final class Runner {
    record Event(double t, String text) {}

    static final class Result {
        final List<Window> windows = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        String abortReason;            // null when the run completed
        boolean stoppedByUser;
        long totalOps, totalErrors, insertedRows;
        int peakThreadsReached, backoffs;
        double bestOpsPerSec;
        Map<String, Long> topErrors = Map.of();
    }

    private final Profile p;
    private final Workload workload;
    private final PrintStream out;
    private final Path stopFile;
    private final long salt = (System.currentTimeMillis() / 1000) % 1_000_000;

    private final AtomicInteger target = new AtomicInteger(0);
    private final AtomicLong inserted = new AtomicLong();
    private final LongAdder[] opCount = new LongAdder[Profile.OPS.length];
    private final LongAdder errors = new LongAdder();
    private final Hist hist = new Hist();
    private final Map<String, LongAdder> errorKinds = new ConcurrentHashMap<>();
    private volatile boolean stop;
    private volatile boolean userStop;

    Runner(Profile p, PrintStream out, Path stopFile) {
        this.p = p;
        this.workload = new Workload(p);
        this.out = out;
        this.stopFile = stopFile;
        for (int i = 0; i < opCount.length; i++) opCount[i] = new LongAdder();
    }

    /** Ask the run to ramp down and finish (Ctrl-C, STOP file). */
    void requestStop() {
        userStop = true;
    }

    Result run() throws Exception {
        Result r = new Result();
        Shape shape = p.shape();
        Governor gov = p.governor();

        try (Connection control = DriverManager.getConnection(p.jdbc)) {
            long have = Table.count(control);
            if (have < p.rows) throw new IllegalStateException("table " + Table.NAME + " has " + have + " rows, expected " + p.rows + ": run `prepare` first");
            int nodes = serverNodes(control);
            if (p.nodesExpected > 0 && nodes < p.nodesExpected)
                throw new IllegalStateException("only " + nodes + " of " + p.nodesExpected + " server nodes are online: not starting a load test on a degraded cluster");
            out.printf("cluster: %d server nodes, table %s with %d rows. Planned: %d threads peak (hard cap %d), %.0f s, %s%n", nodes, Table.NAME, have,
                p.peakThreads, p.maxThreads, shape.totalSec(), "SLO p95 " + (int) p.sloP95Ms + " ms, errors " + p.sloErrorPct + "%");

            Thread[] workers = new Thread[p.peakThreads];
            for (int i = 0; i < workers.length; i++) {
                final int idx = i;
                workers[i] = new Thread(() -> work(idx), "load-" + i);
                workers[i].setDaemon(true);
                workers[i].start();
            }

            long start = System.nanoTime();
            long[] prev = hist.snapshot();
            long[] prevOps = new long[opCount.length];
            long prevErr = 0;
            int controlFailures = 0;
            int window = 0;
            int threads = Math.min(shape.threadsAt(0), gov.ceiling());
            target.set(threads);
            while (!stop) {
                long due = start + (long) (++window) * p.windowSec * 1_000_000_000L;
                while (System.nanoTime() < due && !stop && !userStop) Thread.sleep(100);
                double t = (System.nanoTime() - start) / 1e9;
                boolean ending = t >= shape.totalSec() || userStop || Files.exists(stopFile);
                if (Files.exists(stopFile) && !userStop) { userStop = true; r.events.add(new Event(t, "STOP file found: ramping down")); }

                // close the window that just ended
                long[] cur = hist.snapshot();
                long[] delta = Hist.minus(cur, prev);
                prev = cur;
                long ops = Hist.total(delta);
                long errNow = errors.sum();
                long errs = errNow - prevErr;
                prevErr = errNow;
                long[] byOp = new long[opCount.length];
                for (int i = 0; i < byOp.length; i++) { long v = opCount[i].sum(); byOp[i] = v - prevOps[i]; prevOps[i] = v; }
                double secs = Math.max(0.001, t - (r.windows.isEmpty() ? 0 : r.windows.get(r.windows.size() - 1).t()));
                double errPct = ops + errs == 0 ? 0 : 100.0 * errs / (ops + errs);
                double p95 = Hist.percentileMs(delta, 95);
                int planned = shape.threadsAt(Math.max(0, t - secs / 2));
                Window w = new Window(t, planned, gov.ceiling(), threads, ops, errs, ops / secs, errs / secs, errPct,
                    Hist.percentileMs(delta, 50), p95, Hist.percentileMs(delta, 99), byOp);
                r.windows.add(w);
                r.totalOps += ops;
                r.bestOpsPerSec = Math.max(r.bestOpsPerSec, w.opsPerSec());
                r.peakThreadsReached = Math.max(r.peakThreadsReached, threads);
                out.printf("t=%4.0fs %-14s threads %2d (ceiling %2d)  %7.0f ops/s  p50 %5.1f  p95 %6.1f  p99 %6.1f ms  errors %.1f%%%n", t, shape.phaseAt(t - secs / 2), threads,
                    gov.ceiling(), w.opsPerSec(), w.p50Ms(), w.p95Ms(), w.p99Ms(), errPct);
                if (ending) break;

                // guardrails
                Governor.Decision d = gov.observe(ops, errs, p95, threads);
                if (d.action() == Governor.Action.BACKOFF) {
                    r.backoffs++;
                    r.events.add(new Event(t, "backed off to ceiling " + d.ceiling() + " (" + d.reason() + ")"));
                    out.println("   >> backing off: ceiling " + d.ceiling() + " (" + d.reason() + ")");
                } else if (d.action() == Governor.Action.RECOVER) {
                    r.events.add(new Event(t, "ceiling raised to " + d.ceiling()));
                    out.println("   >> recovering: ceiling " + d.ceiling());
                } else if (d.action() == Governor.Action.ABORT) {
                    r.abortReason = d.reason();
                    r.events.add(new Event(t, "ABORT: " + d.reason()));
                    out.println("   >> ABORTING: " + d.reason());
                    break;
                }
                try {
                    int n = serverNodes(control);
                    controlFailures = 0;
                    if (p.nodesExpected > 0 && n < p.nodesExpected) {
                        r.abortReason = "server node lost: " + n + " of " + p.nodesExpected + " online";
                        r.events.add(new Event(t, "ABORT: " + r.abortReason));
                        out.println("   >> ABORTING: " + r.abortReason);
                        break;
                    }
                } catch (SQLException e) {
                    if (++controlFailures >= 3) {
                        r.abortReason = "cluster health check failing: " + e.getMessage();
                        r.events.add(new Event(t, "ABORT: " + r.abortReason));
                        out.println("   >> ABORTING: " + r.abortReason);
                        break;
                    }
                }
                threads = Math.min(shape.threadsAt(t), gov.ceiling());
                target.set(threads);
            }

            // ramp straight down and clean up
            r.stoppedByUser = userStop && r.abortReason == null;
            target.set(0);
            stop = true;
            for (Thread w : workers) w.join(10_000);
            r.totalErrors = errors.sum();
            r.insertedRows = inserted.get();
            try {
                Table.removeInserted(control, p.rows);
            } catch (SQLException e) {
                out.println("warning: could not remove the inserted rows: " + e.getMessage());
            }
        }
        Map<String, Long> top = new java.util.LinkedHashMap<>();
        errorKinds.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(5).forEach(e -> top.put(e.getKey(), e.getValue().sum()));
        r.topErrors = top;
        return r;
    }

    private static int serverNodes(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sys.nodes WHERE is_client = FALSE")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------ workers

    private static final String READ = "SELECT id, customer_id, balance, status, payload FROM " + Table.NAME + " WHERE id = ?";
    private static final String UPDATE = "UPDATE " + Table.NAME + " SET balance = balance + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
    private static final String INSERT = "INSERT INTO " + Table.NAME + " (id, customer_id, balance, status, region, payload, updated_at) VALUES (?, ?, 0, 'ACTIVE', 'EU', ?, CURRENT_TIMESTAMP)";
    private static final String RANGE = "SELECT id, balance, status FROM " + Table.NAME + " WHERE customer_id = ?";
    /** A two-row transfer as one statement: both balances change together or not at all. */
    private static final String TRANSFER = "UPDATE " + Table.NAME + " SET balance = balance + CASE WHEN id = ? THEN ? ELSE ? END WHERE id IN (?, ?)";

    private void work(int idx) {
        Random rnd = new Random(salt * 1009 + idx);
        Connection c = null;
        PreparedStatement read = null, update = null, insert = null, range = null, transfer = null;
        long seq = 0;
        while (!stop) {
            if (idx >= target.get()) {
                sleep(50);
                continue;
            }
            Op op = workload.pick(rnd);
            if (op == Op.INSERT && inserted.get() >= p.maxInsertRows) op = Op.READ;     // the table does not grow without bound
            long t0 = System.nanoTime();
            try {
                if (c == null || c.isClosed()) {
                    c = DriverManager.getConnection(p.jdbc);
                    read = c.prepareStatement(READ); update = c.prepareStatement(UPDATE); insert = c.prepareStatement(INSERT);
                    range = c.prepareStatement(RANGE); transfer = c.prepareStatement(TRANSFER);
                    for (PreparedStatement ps : new PreparedStatement[] {read, update, insert, range, transfer}) ps.setQueryTimeout(p.queryTimeoutSec);
                }
                switch (op) {
                    case READ -> { read.setLong(1, workload.key(rnd)); drain(read.executeQuery()); }
                    case UPDATE -> { update.setInt(1, 1 + rnd.nextInt(100)); update.setLong(2, workload.key(rnd)); update.executeUpdate(); }
                    case INSERT -> {
                        insert.setLong(1, workload.newId(salt, idx, ++seq));
                        insert.setInt(2, workload.customer(rnd));
                        insert.setString(3, "p" + Long.toHexString(rnd.nextLong()) + Long.toHexString(rnd.nextLong()));
                        insert.executeUpdate();
                        inserted.incrementAndGet();
                    }
                    case RANGE -> { range.setInt(1, workload.customer(rnd)); drain(range.executeQuery()); }
                    case TX -> {
                        long a = workload.key(rnd), b = workload.key(rnd);
                        if (a == b) b = a == p.rows ? a - 1 : a + 1;
                        int d = 1 + rnd.nextInt(100);
                        transfer.setLong(1, a); transfer.setInt(2, -d); transfer.setInt(3, d); transfer.setLong(4, a); transfer.setLong(5, b);
                        transfer.executeUpdate();
                    }
                }
                hist.record(System.nanoTime() - t0);
                opCount[op.ordinal()].increment();
            } catch (SQLException | RuntimeException e) {
                errors.increment();
                String msg = String.valueOf(e.getMessage());
                errorKinds.computeIfAbsent(op + ": " + msg.substring(0, Math.min(90, msg.length())).replace('\n', ' '), k -> new LongAdder()).increment();
                try { if (c != null && !c.isValid(1)) { c.close(); c = null; } } catch (SQLException ignored) { c = null; }
                sleep(c == null ? 500 : 20);      // do not hammer a cluster that is refusing connections
            }
            if (p.thinkMs > 0) sleep(p.thinkMs);
        }
        try { if (c != null) c.close(); } catch (SQLException ignored) { }
    }

    private static void drain(ResultSet rs) throws SQLException {
        try (rs) {
            while (rs.next()) { /* fetch every row */ }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
