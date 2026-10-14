package poc.compute.jobs;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import org.apache.ignite.compute.ComputeJob;

/** Replaces sp_recalc_loyalty_tier: derive the tier from trailing-12-month spend and update if it changed. */
public class RecalcLoyaltyTierTask extends AffinityTask<Integer> {
    @Override
    int customerId(Integer cid) {
        return cid;
    }

    @Override
    ComputeJob job(Integer cid) {
        return new Job(cid);
    }

    static class Job extends CustomerJob {
        private final int cid;

        Job(int cid) {
            this.cid = cid;
        }

        @Override
        public Object execute() {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String oldTier = (String) Sql.scalar(ignite, "SELECT loyalty_tier FROM customer WHERE customer_id = ?", cid);
            if (oldTier == null) return fail(-1, 20002, "Customer not found");

            BigDecimal spend = Loyalty.spend12m(ignite, cid, now);
            String newTier = Loyalty.tierForSpend(ignite, spend);
            boolean changed = !newTier.equals(oldTier);
            if (changed) {
                Sql.exec(ignite, "UPDATE customer SET loyalty_tier = ?, updated_at = ? WHERE customer_id = ?",
                    newTier, now, cid);
            }
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("customer_id", cid);
            out.put("old_tier", oldTier);
            out.put("new_tier", newTier);
            out.put("changed", changed);
            out.put("spend_12m", spend);
            return out;
        }
    }
}
