package poc.compute.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignition;
import org.apache.ignite.client.ClientCompute;
import org.apache.ignite.client.ClientRetryAllPolicy;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.ClientConfiguration;

/** CLI for the GridGain compute jobs that replace the Sybase stored procedures. */
public final class Main {
    static final String PKG = "poc.compute.jobs.";

    public static void main(String[] a) throws Exception {
        if (a.length == 0) usage();
        try (IgniteClient client = connect()) {
            ClientCompute compute = client.compute().withTimeout(120_000);
            switch (a[0]) {
                case "360": print(compute.execute(PKG + "Customer360Task", Integer.parseInt(a[1]))); break;
                case "recalc": print(compute.execute(PKG + "RecalcLoyaltyTierTask", Integer.parseInt(a[1]))); break;
                case "score": print(compute.execute(PKG + "ScoreCustomersTask", a.length > 1 ? Long.valueOf(a[1]) : null)); break;
                case "order": print(compute.execute(PKG + "PlaceOrderTask", orderArgs(a))); break;
                case "trips": print(compute.execute(PKG + "TripsTask", Integer.parseInt(a[1]))); break;
                case "hub": print(compute.execute(PKG + "CustomerHubTask", Integer.parseInt(a[1]))); break;
                case "impact": print(compute.execute(PKG + "DisruptionImpactTask", Integer.parseInt(a[1]))); break;
                case "cancel-booking": print(compute.execute(PKG + "CancelBookingTask", Integer.parseInt(a[1]))); break;
                case "book": print(compute.execute(PKG + "BookFlightTask", bookArgs(a))); break;
                case "delay": print(compute.execute(PKG + "RecordDelayTask", flightOp(a, false))); break;
                case "cancel-flight": print(compute.execute(PKG + "RecordDelayTask", flightOp(a, true))); break;
                case "disruptions": {
                    Map<String, Object> m = new HashMap<>();
                    if (a.length > 1) m.put("min_delay", Integer.parseInt(a[1]));
                    print(compute.execute(PKG + "ProcessDisruptionsTask", m));
                    break;
                }
                case "offers": {   // offers [customer_id|all] [top_n]
                    Map<String, Object> m = new HashMap<>();
                    if (a.length > 1 && !a[1].equals("all")) m.put("customer_id", Integer.parseInt(a[1]));
                    if (a.length > 2) m.put("top_n", Integer.parseInt(a[2]));
                    print(compute.execute(PKG + "RecommendOffersTask", m));
                    break;
                }
                case "parity":
                    System.exit((a.length > 1 && a[1].equals("oracle") ? new ParityOracle(client).run() : new ParitySybase(client).run(a)) ? 0 : 1);
                    break;
                default: usage();
            }
        }
    }

    static IgniteClient connect() {
        // All three nodes, so the client fails over if one is down. Affinity awareness is off because the
        // nodes advertise their container-network addresses, which are not reachable from the host.
        String addr = System.getenv().getOrDefault("GG_ADDR", "127.0.0.1:10800,127.0.0.1:10801,127.0.0.1:10802");
        return Ignition.startClient(new ClientConfiguration().setAddresses(addr.split(","))
            .setAffinityAwarenessEnabled(false).setRetryPolicy(new ClientRetryAllPolicy()));
    }

    /** order <customer_id> <channel> <payment_method> <product:qty,product:qty,...> */
    static Map<String, Object> orderArgs(String[] a) {
        return orderArgs(Integer.parseInt(a[1]), a[2], a[3], a[4]);
    }

    static Map<String, Object> orderArgs(int cid, String channel, String method, String items) {
        List<Object> flat = new ArrayList<>();
        if (!items.isBlank()) {
            for (String it : items.split(",")) {
                String[] pq = it.split(":");
                flat.add(Integer.parseInt(pq[0]));
                flat.add(Integer.parseInt(pq[1]));
            }
        }
        Map<String, Object> m = new HashMap<>();
        m.put("customer_id", cid);
        m.put("channel", channel);
        m.put("payment_method", method);
        m.put("items", flat);
        return m;
    }

    /** book <customer_id> <flight_id> <cabin> <passenger> [channel] */
    static Map<String, Object> bookArgs(String[] a) {
        Map<String, Object> m = new HashMap<>();
        m.put("customer_id", Integer.parseInt(a[1]));
        m.put("flight_id", Integer.parseInt(a[2]));
        m.put("cabin", a[3]);
        m.put("passenger", a[4]);
        if (a.length > 5) m.put("channel", a[5]);
        return m;
    }

    /** delay <flight_id> <minutes> [reason]   |   cancel-flight <flight_id> [reason] */
    static Map<String, Object> flightOp(String[] a, boolean cancel) {
        Map<String, Object> m = new HashMap<>();
        m.put("flight_id", Integer.parseInt(a[1]));
        m.put("cancel", cancel);
        int r = cancel ? 2 : 3;
        if (!cancel) m.put("minutes", Integer.parseInt(a[2]));
        if (a.length > r) m.put("reason", a[r]);
        return m;
    }

    static void print(Object o) {
        print(o, 0);
    }

    private static void print(Object o, int ind) {
        String pad = "  ".repeat(ind);
        if (o instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (e.getValue() instanceof Map || e.getValue() instanceof List) {
                    System.out.println(pad + e.getKey() + ":");
                    print(e.getValue(), ind + 1);
                } else {
                    System.out.println(pad + e.getKey() + " = " + e.getValue());
                }
            }
        } else if (o instanceof List) {
            for (Object x : (List<?>) o) {
                if (x instanceof Map) {
                    StringBuilder sb = new StringBuilder();
                    ((Map<?, ?>) x).forEach((k, v) -> sb.append(k).append('=').append(v).append("  "));
                    System.out.println(pad + sb.toString().trim());
                } else {
                    System.out.println(pad + x);
                }
            }
        } else {
            System.out.println(pad + o);
        }
    }

    private static void usage() {
        System.err.println("usage: run.sh 360 <customer_id> | recalc <customer_id> | score [asOfMillis]\n"
            + "       run.sh order <customer_id> <channel> <method> <product:qty,...>\n"
            + "       run.sh trips <customer_id> | hub <customer_id> | impact <flight_id> | cancel-booking <booking_id>\n"
            + "       run.sh book <customer_id> <flight_id> <cabin> <passenger> [channel] | delay <flight_id> <minutes> [reason]\n"
            + "       run.sh cancel-flight <flight_id> [reason] | disruptions [min_delay] | offers [customer_id|all] [top_n]\n"
            + "       run.sh parity <360|recalc|score|order|errors|all|oracle>");
        System.exit(2);
    }
}
