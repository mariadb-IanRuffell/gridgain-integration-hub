package poc.compute.client;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ignite.client.ClientCompute;
import org.apache.ignite.client.IgniteClient;

/**
 * Side-by-side parity: run each MariaDB stored procedure and its GridGain compute job on identical inputs
 * and compare the outputs value by value. MariaDB is reached over Connector/J, GridGain over the thin client
 * (compute) and thin JDBC (reading tables back).
 */
final class ParityMariaDb {
    private static final String PKG = Main.PKG;

    private final ClientCompute compute;
    private Connection db, gg;
    private int passed, failed;

    ParityMariaDb(IgniteClient client) {
        this.compute = client.compute().withTimeout(120_000);
    }

    boolean run(String[] a) throws Exception {
        String what = a.length > 1 ? a[1] : "all";
        db = DriverManager.getConnection(env("MARIADB_URL", "jdbc:mariadb://localhost:3306/crm"),
            env("MARIADB_USER", "crm"), env("MARIADB_PASSWORD", "crm"));
        gg = DriverManager.getConnection(env("GG_JDBC", "jdbc:ignite:thin://127.0.0.1:10800,127.0.0.1:10801,127.0.0.1:10802"));
        try {
            boolean all = what.equals("all");
            if (all || what.equals("360")) parity360();
            if (all || what.equals("recalc")) parityRecalc();
            if (all || what.equals("score")) parityScore();
            if (all || what.equals("errors")) parityErrors();
            if (all || what.equals("order")) parityOrder();
        } finally {
            db.close();
            gg.close();
        }
        System.out.printf("%n%s: %d checks passed, %d failed%n", failed == 0 ? "PARITY OK" : "PARITY FAILED", passed, failed);
        return failed == 0;
    }

    // ------------------------------------------------------------------ sp_customer_360


    private void parity360() throws Exception {
        System.out.println("== sp_customer_360 vs Customer360Task (every customer, all 6 result sets)");
        String[] sets = {"profile", "order_summary", "recent_orders", "top_categories", "interactions", "loyalty"};
        List<Integer> ids = ints(db, "select customer_id from customer order by customer_id");
        int bad = 0;
        for (int cid : ids) {
            List<List<List<Object>>> src = mariadb360(cid);
            Map<String, Object> job = compute.execute(PKG + "Customer360Task", cid);
            for (int s = 0; s < sets.length; s++) {
                List<List<Object>> expected = src.get(s);
                List<List<Object>> actual = rows((List<?>) job.get(sets[s]));
                String diff = compareRows(expected, actual);
                if (diff != null) {
                    if (bad++ < 5) System.out.println("   customer " + cid + " set '" + sets[s] + "': " + diff);
                }
            }
        }
        check("sp_customer_360 parity over " + ids.size() + " customers x 6 result sets", bad == 0,
            bad + " differences");
    }

    private List<List<List<Object>>> mariadb360(int cid) throws SQLException {
        List<List<List<Object>>> out = new ArrayList<>();
        try (CallableStatement cs = db.prepareCall("{call sp_customer_360(?)}")) {
            cs.setInt(1, cid);
            boolean has = cs.execute();
            while (true) {
                if (has) out.add(readAll(cs.getResultSet()));
                else if (cs.getUpdateCount() == -1) break;
                has = cs.getMoreResults();
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sp_recalc_loyalty_tier

    private void parityRecalc() throws Exception {
        System.out.println("== sp_recalc_loyalty_tier vs RecalcLoyaltyTierTask (every customer)");
        List<Integer> ids = ints(db, "select customer_id from customer order by customer_id");
        int bad = 0, changed = 0;
        for (int cid : ids) {
            String dbTier;
            try (CallableStatement cs = db.prepareCall("{call sp_recalc_loyalty_tier(?, ?)}")) {
                cs.setInt(1, cid);
                cs.registerOutParameter(2, Types.VARCHAR);
                cs.execute();
                while (cs.getMoreResults() || cs.getUpdateCount() != -1) { }
                dbTier = cs.getString(2);
            }
            Map<String, Object> job = compute.execute(PKG + "RecalcLoyaltyTierTask", cid);
            if (Boolean.TRUE.equals(job.get("changed"))) changed++;
            if (!dbTier.equals(job.get("new_tier"))) {
                if (bad++ < 5) System.out.println("   customer " + cid + ": mariadb=" + dbTier + " job=" + job.get("new_tier"));
            }
        }
        check("tier returned for " + ids.size() + " customers", bad == 0, bad + " differences");
        String diff = compareRows(readAll(db, "select customer_id, loyalty_tier from customer order by customer_id"),
            readAll(gg, "select customer_id, loyalty_tier from customer order by customer_id"));
        check("customer.loyalty_tier identical in both systems afterwards (" + changed + " tiers changed by the job)",
            diff == null, String.valueOf(diff));
    }

    // ------------------------------------------------------------------ sp_score_customers

    private void parityScore() throws Exception {
        System.out.println("== sp_score_customers (cursor) vs ScoreCustomersTask (per-node set-based)");
        long asOf = System.currentTimeMillis() / 1000 * 1000;   // whole seconds: MariaDB DATETIME has whole seconds
        List<List<Object>> expectedBands;
        try (CallableStatement cs = db.prepareCall("{call sp_score_customers(?, ?)}")) {
            cs.setTimestamp(1, new Timestamp(asOf));
            cs.setNull(2, Types.INTEGER);
            boolean has = cs.execute();
            expectedBands = new ArrayList<>();
            while (true) {
                if (has) expectedBands.addAll(readAll(cs.getResultSet()));
                else if (cs.getUpdateCount() == -1) break;
                has = cs.getMoreResults();
            }
        }
        List<?> job = compute.execute(PKG + "ScoreCustomersTask", asOf);
        check("band summary (risk_band, customers, avg_score)", compareRows(expectedBands, rows(job)) == null,
            "mariadb=" + db + " job=" + rows(job));
        String q = "select customer_id, churn_score, risk_band, scored_at from customer_score order by customer_id";
        List<List<Object>> st = readAll(db, q), gt = readAll(gg, q);
        String diff = compareRows(st, gt);
        check("customer_score identical for all " + st.size() + " customers (score, band, scored_at)", diff == null,
            String.valueOf(diff));
    }

    // ------------------------------------------------------------------ order flow

    private void parityOrder() throws Exception {
        System.out.println("== sp_create_order + sp_add_order_item + sp_complete_order vs PlaceOrderTask");
        int cid = scalarInt(db, "select min(customer_id) from customer where status = 'ACTIVE' and loyalty_tier = 'SILVER'");
        int[][] items = {{4, 2}, {17, 1}, {9, 3}};
        String itemSpec = "4:2,17:1,9:3";
        System.out.println("   customer " + cid + ", items " + itemSpec);

        List<List<Object>> before = readAll(db, stateSql(cid));
        String pre = compareRows(before, readAll(gg, stateSql(cid)));
        if (pre != null) {
            check("GridGain in sync with MariaDB before the test (run cdc init)", false, pre);
            return;
        }

        Map<String, Object> job = compute.execute(PKG + "PlaceOrderTask", Main.orderArgs(cid, "APP", "CARD", itemSpec));
        int gOrder = (Integer) job.get("order_id");

        int sOrder = mariadbOrder(cid, items);

        BigDecimal sTotal = (BigDecimal) readAll(db, "select total_amount from orders where order_id = " + sOrder).get(0).get(0);
        check("order total", same(sTotal, job.get("total_amount")), sTotal + " vs " + job.get("total_amount"));
        check("line items created", same(scalarInt(db, "select count(*) from order_item where order_id = " + sOrder),
            scalarInt(gg, "select count(*) from order_item where order_id = " + gOrder + " and customer_id = " + cid)), "");
        check("payment captured", same(readAll(db, "select amount from payment where order_id = " + sOrder).get(0).get(0),
            readAll(gg, "select amount from payment where order_id = " + gOrder + " and customer_id = " + cid).get(0).get(0)), "");
        check("loyalty points earned", same(readAll(db, "select points from loyalty_txn where order_id = " + sOrder).get(0).get(0),
            readAll(gg, "select points from loyalty_txn where order_id = " + gOrder + " and customer_id = " + cid).get(0).get(0)), "");
        List<List<Object>> sAfter = readAll(db, stateSql(cid));
        List<List<Object>> gAfter = readAll(gg, stateSql(cid));
        String diff = compareRows(sAfter, gAfter);
        check("customer tier/points and product stock identical afterwards", diff == null, String.valueOf(diff));
        System.out.println("   tier " + before.get(0).get(1) + " -> " + sAfter.get(0).get(1)
            + ", points " + before.get(0).get(2) + " -> " + sAfter.get(0).get(2));
        System.out.println("   NOTE: MariaDB and GridGain now hold different orders for this test. Resync with: ../cdc/run.sh init");
    }

    /** Customer state plus the stock of the products used, as one comparable result set. */
    private static String stateSql(int cid) {
        return "select customer_id, loyalty_tier, loyalty_points from customer where customer_id = " + cid
            + " union all select product_id, sku, stock_qty from product where product_id in (4, 17, 9)";
    }

    private int mariadbOrder(int cid, int[][] items) throws SQLException {
        int oid;
        try (CallableStatement cs = db.prepareCall("{call sp_create_order(?, ?, ?)}")) {
            cs.setInt(1, cid);
            cs.setString(2, "APP");
            cs.registerOutParameter(3, Types.INTEGER);
            cs.execute();
            oid = cs.getInt(3);
        }
        for (int[] it : items) {
            try (CallableStatement cs = db.prepareCall("{call sp_add_order_item(?, ?, ?)}")) {
                cs.setInt(1, oid);
                cs.setInt(2, it[0]);
                cs.setInt(3, it[1]);
                cs.execute();
            }
        }
        try (CallableStatement cs = db.prepareCall("{call sp_complete_order(?, ?)}")) {
            cs.setInt(1, oid);
            cs.setString(2, "CARD");
            cs.execute();
        }
        return oid;
    }

    // ------------------------------------------------------------------ error behaviour

    private void parityErrors() throws Exception {
        System.out.println("== error behaviour: same error number for the same bad input");
        int cid = scalarInt(db, "select min(customer_id) from customer where status = 'ACTIVE'");
        int emptyOrder;
        try (CallableStatement cs = db.prepareCall("{call sp_create_order(?, ?, ?)}")) {
            cs.setInt(1, cid);
            cs.setString(2, "WEB");
            cs.registerOutParameter(3, Types.INTEGER);
            cs.execute();
            emptyOrder = cs.getInt(3);
        }
        try {
            errorCase("unknown customer", 20002, "call sp_create_order(999999, 'WEB', @o)", false,
                Main.orderArgs(999999, "WEB", "CARD", "4:1"));
            errorCase("quantity zero", 20004, "call sp_add_order_item(" + emptyOrder + ", 4, 0)", false,
                Main.orderArgs(cid, "WEB", "CARD", "4:0"));
            errorCase("unknown product", 20006, "call sp_add_order_item(" + emptyOrder + ", 99999, 1)", false,
                Main.orderArgs(cid, "WEB", "CARD", "99999:1"));
            errorCase("insufficient stock", 20003, "call sp_add_order_item(" + emptyOrder + ", 4, 999999)", false,
                Main.orderArgs(cid, "WEB", "CARD", "4:999999"));
            errorCase("empty order", 20007, "call sp_complete_order(" + emptyOrder + ", 'CARD')", false,
                Main.orderArgs(cid, "WEB", "CARD", ""));
        } finally {
            try (Statement s = db.createStatement()) {
                s.executeUpdate("delete from orders where order_id = " + emptyOrder);   // remove the scratch order
            }
        }
        // the failed jobs must not have changed anything: stock still equals MariaDB's
        check("failed orders left product stock untouched",
            compareRows(readAll(db, "select product_id, stock_qty from product order by product_id"),
                readAll(gg, "select product_id, stock_qty from product order by product_id")) == null, "");
    }

    private void errorCase(String name, int expected, String dbCall, boolean outParam, Map<String, Object> jobArg)
            throws Exception {
        int dbErr = 0;
        try (Statement st = db.createStatement()) {
            st.execute(dbCall);
        } catch (SQLException e) {
            for (SQLException x = e; x != null && dbErr == 0; x = x.getNextException()) dbErr = x.getErrorCode();
            if (dbErr == 0) System.out.println("   (unparsed MariaDB error: " + e + ")");
        }
        Map<String, Object> job = compute.execute(PKG + "PlaceOrderTask", jobArg);
        Object jobErr = job.get("error_no");
        check(name + ": MariaDB raises " + dbErr + ", job returns " + jobErr,
            dbErr == expected && Integer.valueOf(expected).equals(jobErr), "expected " + expected);
    }

    // ------------------------------------------------------------------ helpers

    private void check(String what, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.println("   " + (ok ? "PASS " : "FAIL ") + what + (ok ? "" : "  -> " + detail));
    }

    static List<List<Object>> readAll(ResultSet rs) throws SQLException {
        List<List<Object>> out = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) row.add(rs.getObject(i));
            out.add(row);
        }
        rs.close();
        return out;
    }

    static List<List<Object>> readAll(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            return readAll(s.executeQuery(sql));
        }
    }

    static List<Integer> ints(Connection c, String sql) throws SQLException {
        List<Integer> out = new ArrayList<>();
        for (List<Object> r : readAll(c, sql)) out.add(((Number) r.get(0)).intValue());
        return out;
    }

    static int scalarInt(Connection c, String sql) throws SQLException {
        return ((Number) readAll(c, sql).get(0).get(0)).intValue();
    }

    /** Job result (list of column->value maps) as rows of values in column order. */
    static List<List<Object>> rows(List<?> maps) {
        List<List<Object>> out = new ArrayList<>();
        for (Object m : maps) out.add(new ArrayList<>(((Map<?, ?>) m).values()));
        return out;
    }

    /** null when identical (within tolerance), otherwise a short description of the first difference. */
    static String compareRows(List<List<Object>> expected, List<List<Object>> actual) {
        if (expected.size() != actual.size()) return "row count " + expected.size() + " vs " + actual.size();
        for (int i = 0; i < expected.size(); i++) {
            List<Object> e = expected.get(i), a = actual.get(i);
            if (e.size() != a.size()) return "row " + i + " column count " + e.size() + " vs " + a.size();
            for (int j = 0; j < e.size(); j++) {
                if (!same(e.get(j), a.get(j))) return "row " + i + " col " + j + ": mariadb=" + e + " gridgain=" + a;
            }
        }
        return null;
    }

    /** Value equality tolerant of MariaDB/H2 differences: decimal scale, float precision, datetime rounding. */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).subtract(new BigDecimal(b.toString())).abs()
                .compareTo(new BigDecimal("0.01")) <= 0;
        }
        if (a instanceof Timestamp && b instanceof Timestamp) {
            return Math.abs(((Timestamp) a).getTime() - ((Timestamp) b).getTime()) <= 10;
        }
        if (a instanceof String && b instanceof String) return ((String) a).stripTrailing().equals(((String) b).stripTrailing());
        return a.equals(b);
    }

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
