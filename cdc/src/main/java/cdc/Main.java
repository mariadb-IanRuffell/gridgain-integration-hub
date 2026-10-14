package cdc;

import java.sql.SQLException;

public final class Main {
    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "help";
        String aseUrl = env("ASE_URL", "jdbc:jtds:sybase://llama.local:5000/testdb");
        String ggUrl = env("GG_URL", "jdbc:ignite:thin://127.0.0.1:10800,127.0.0.1:10801,127.0.0.1:10802");
        long intervalMs = Long.parseLong(env("CDC_POLL_MS", "1000"));
        int batch = Integer.parseInt(env("CDC_BATCH", "5000"));

        if (!java.util.Set.of("init", "run", "once", "status", "verify").contains(cmd)) {
            System.err.println("usage: run.sh init | run | once | status | verify");
            System.exit(2);
        }
        try (Cdc cdc = new Cdc(aseUrl, env("ASE_USER", "tester"), env("ASE_PASSWORD", "guest1234"), ggUrl)) {
            switch (cmd) {
                case "init" -> cdc.init();
                case "status" -> cdc.status();
                case "verify" -> System.exit(cdc.verify() ? 0 : 1);
                case "once" -> {
                    Cdc.Result r = cdc.applyOnce(batch);
                    System.out.printf("events=%d upserts=%d deletes=%d offset=%d%n", r.events(), r.upserts(), r.deletes(), r.lastChangeId());
                }
                case "run" -> stream(cdc, batch, intervalMs);
                default -> { }
            }
        }
    }

    private static void stream(Cdc cdc, int batch, long intervalMs) throws Exception {
        System.out.println("streaming Sybase -> GridGain (poll " + intervalMs + " ms, batch " + batch + "); Ctrl-C to stop");
        Metrics metrics = new Metrics(cdc);
        metrics.ensureTables();
        while (true) {
            try {
                Cdc.Result r = cdc.applyOnce(batch);
                metrics.record(r);
                metrics.tick();
                if (r.events() > 0) {
                    System.out.printf("%tT applied events=%d upserts=%d deletes=%d offset=%d%n",
                        new java.util.Date(), r.events(), r.upserts(), r.deletes(), r.lastChangeId());
                } else {
                    Thread.sleep(intervalMs);
                }
            } catch (SQLException e) {
                System.err.println("error: " + e.getMessage() + " - reconnecting in 5s");
                Thread.sleep(5000);
                try { cdc.connect(); } catch (SQLException ce) { System.err.println("reconnect failed: " + ce.getMessage()); }
            }
        }
    }
}
