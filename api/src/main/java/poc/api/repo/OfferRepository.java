package poc.api.repo;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import poc.api.model.Dto.CategoryCount;
import poc.api.model.Dto.Offer;
import poc.api.model.Dto.OfferSummary;
import poc.api.model.Dto.Page;

/** Read access to the offers written by the RecommendOffersTask compute job (table customer_offer, colocated on customer_id). */
@Repository
public class OfferRepository {
    private static final Logger log = LoggerFactory.getLogger(OfferRepository.class);
    private static final String COLS = "customer_id, rank_no, product_id, sku, product_name, category, score, discount_pct, list_price, offer_price, "
        + "reason, generated_at, expires_at";

    /** Same definition as RecommendOffersTask.DDL (the job also creates the table on first use). */
    static final String DDL = "CREATE TABLE IF NOT EXISTS customer_offer (customer_id INT, product_id INT, rank_no INT, sku VARCHAR(20), "
        + "product_name VARCHAR(100), category VARCHAR(12), score DOUBLE, discount_pct INT, list_price DECIMAL(10,2), offer_price DECIMAL(10,2), "
        + "reason VARCHAR(300), generated_at TIMESTAMP, expires_at TIMESTAMP, PRIMARY KEY (customer_id, product_id)) "
        + "WITH \"template=partitioned,backups=1,affinity_key=customer_id,cache_name=customer_offer,atomicity=transactional\"";

    private final JdbcClient jdbc;

    OfferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Create the table at start-up so reads work before the first generate run; a cluster that is not up yet is not fatal. */
    @EventListener(ApplicationReadyEvent.class)
    void ensureTable() {
        try {
            jdbc.sql(DDL).update();
        } catch (RuntimeException e) {
            log.warn("customer_offer not created yet (the offers job creates it on first use): {}", e.getMessage());
        }
    }

    private static Offer map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Cols c = new Cols(rs);
        Integer customerId = c.integer(), rank = c.integer(), productId = c.integer();
        String sku = c.string(), name = c.string(), category = c.string();
        Double score = c.dbl();
        return new Offer(customerId, rank, productId, sku, name, category, score, c.integer(), c.decimal(), c.decimal(), c.string(), c.instant(), c.instant());
    }

    public Page<Offer> list(Integer customerId, String category, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (customerId != null) { where.append(" AND customer_id = ?"); args.add(customerId); }
        if (category != null) { where.append(" AND category = ?"); args.add(category); }
        long total = jdbc.sql("SELECT COUNT(*) FROM customer_offer" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Offer> items = jdbc.sql("SELECT " + COLS + " FROM customer_offer" + where + " ORDER BY customer_id, rank_no LIMIT ? OFFSET ?")
            .params(pageArgs).query(OfferRepository::map).list();
        return new Page<>(items, limit, offset, total);
    }

    public List<Offer> forCustomer(int customerId) {
        return jdbc.sql("SELECT " + COLS + " FROM customer_offer WHERE customer_id = ? ORDER BY rank_no").param(customerId)
            .query(OfferRepository::map).list();
    }

    /** Totals, a split by category and the strongest offers, for the dashboard. */
    public OfferSummary summary(int top) {
        long offers = jdbc.sql("SELECT COUNT(*) FROM customer_offer").query(Long.class).single();
        long customers = jdbc.sql("SELECT COUNT(DISTINCT customer_id) FROM customer_offer").query(Long.class).single();
        double avg = jdbc.sql("SELECT COALESCE(AVG(CAST(discount_pct AS DOUBLE)), 0) FROM customer_offer").query(Double.class).single();
        java.time.Instant last = jdbc.sql("SELECT MAX(generated_at) FROM customer_offer")
            .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant()).optional().orElse(null);
        List<CategoryCount> byCategory = jdbc.sql("SELECT category, COUNT(*) FROM customer_offer GROUP BY category ORDER BY COUNT(*) DESC, category")
            .query((rs, n) -> new CategoryCount(rs.getString(1).stripTrailing(), rs.getLong(2))).list();
        List<Offer> best = jdbc.sql("SELECT " + COLS + " FROM customer_offer WHERE rank_no = 1 ORDER BY score DESC, customer_id LIMIT ?").param(top)
            .query(OfferRepository::map).list();
        return new OfferSummary(offers, customers, Math.round(avg * 100) / 100.0, last, byCategory, best);
    }
}
