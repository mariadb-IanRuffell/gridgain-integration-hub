package poc.api.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import poc.api.model.Dto.CustomerScore;
import poc.api.model.Dto.LoyaltyTier;
import poc.api.model.Dto.LoyaltyTransaction;
import poc.api.model.Dto.Page;

/** Read-only loyalty data. Points and tiers are changed by the place-order and recalculate jobs, not directly. */
@Repository
public class LoyaltyRepository {
    private final JdbcClient jdbc;

    LoyaltyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page<LoyaltyTransaction> transactions(int customerId, int limit, int offset) {
        long total = jdbc.sql("SELECT COUNT(*) FROM loyalty_txn WHERE customer_id = ?").param(customerId)
            .query(Long.class).single();
        List<LoyaltyTransaction> items = jdbc.sql("SELECT txn_id, customer_id, order_id, points, reason, txn_date "
                + "FROM loyalty_txn WHERE customer_id = ? ORDER BY txn_date DESC, txn_id DESC LIMIT ? OFFSET ?")
            .params(customerId, limit, offset).query((rs, n) -> {
                Cols c = new Cols(rs);
                return new LoyaltyTransaction(c.integer(), c.integer(), c.integer(), c.integer(), c.string(), c.instant());
            }).list();
        return new Page<>(items, limit, offset, total);
    }

    public List<LoyaltyTier> tiers() {
        return jdbc.sql("SELECT tier, min_spend, points_multiplier FROM loyalty_tier_rule ORDER BY min_spend")
            .query((rs, n) -> {
                Cols c = new Cols(rs);
                return new LoyaltyTier(c.string(), c.decimal(), c.decimal());
            }).list();
    }

    public Optional<CustomerScore> score(int customerId) {
        return jdbc.sql("SELECT customer_id, churn_score, risk_band, scored_at FROM customer_score WHERE customer_id = ?")
            .param(customerId).query((rs, n) -> {
                Cols c = new Cols(rs);
                return new CustomerScore(c.integer(), c.integer(), c.string(), c.instant());
            }).optional();
    }
}
