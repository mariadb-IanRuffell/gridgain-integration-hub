package poc.compute.jobs;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.Ignite;

/** Loyalty rules shared by several jobs (the T-SQL equivalents are inlined in the stored procedures). */
final class Loyalty {
    private Loyalty() {}

    /** Trailing-12-month spend on COMPLETED orders. */
    static BigDecimal spend12m(Ignite ignite, int customerId, Timestamp now) {
        Object v = Sql.scalar(ignite,
            "SELECT COALESCE(SUM(total_amount), 0) FROM orders "
                + "WHERE customer_id = ? AND status = 'COMPLETED' AND order_date >= ?",
            customerId, minusDays(now, 365));
        return (BigDecimal) v;
    }

    /** Tier with the highest threshold not above the spend (loyalty_tier_rule). */
    static String tierForSpend(Ignite ignite, BigDecimal spend) {
        return (String) Sql.scalar(ignite,
            "SELECT tier FROM loyalty_tier_rule WHERE min_spend = "
                + "(SELECT MAX(min_spend) FROM loyalty_tier_rule WHERE min_spend <= ?)", spend);
    }

    static BigDecimal multiplier(Ignite ignite, String tier) {
        return (BigDecimal) Sql.scalar(ignite, "SELECT points_multiplier FROM loyalty_tier_rule WHERE tier = ?", tier);
    }

    static Timestamp minusDays(Timestamp ts, int days) {
        return new Timestamp(ts.getTime() - days * 86_400_000L);
    }

    static List<Object> list(Object... v) {
        List<Object> l = new ArrayList<>();
        for (Object o : v) l.add(o);
        return l;
    }
}
