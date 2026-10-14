package poc.api.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import poc.api.model.Dto.Customer;
import poc.api.model.Dto.CustomerInput;
import poc.api.model.Dto.Page;

@Repository
public class CustomerRepository {
    private static final String COLS = "customer_id, first_name, last_name, email, phone, date_of_birth, nationality, "
        + "segment, loyalty_tier, loyalty_points, marketing_opt_in, status, created_at, updated_at";

    private final JdbcClient jdbc;
    private final Ids ids;

    CustomerRepository(JdbcClient jdbc, Ids ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    private static Customer map(ResultSet rs, int row) throws SQLException {
        Cols c = new Cols(rs);
        return new Customer(c.integer(), c.string(), c.string(), c.string(), c.string(), c.date(), c.string(),
            c.string(), c.string(), c.integer(), c.bool(), c.string(), c.instant(), c.instant());
    }

    public Page<Customer> list(String status, String tier, String email, String q, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) { where.append(" AND status = ?"); args.add(status); }
        if (tier != null) { where.append(" AND loyalty_tier = ?"); args.add(tier); }
        if (email != null) { where.append(" AND LOWER(email) = LOWER(?)"); args.add(email); }
        if (q != null) {
            where.append(" AND (LOWER(first_name) LIKE ? OR LOWER(last_name) LIKE ?)");
            args.add("%" + q.toLowerCase() + "%");
            args.add("%" + q.toLowerCase() + "%");
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM customer" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Customer> items = jdbc.sql("SELECT " + COLS + " FROM customer" + where
                + " ORDER BY customer_id LIMIT ? OFFSET ?")
            .params(pageArgs).query(CustomerRepository::map).list();
        return new Page<>(items, limit, offset, total);
    }

    public Optional<Customer> find(int id) {
        return jdbc.sql("SELECT " + COLS + " FROM customer WHERE customer_id = ?").param(id)
            .query(CustomerRepository::map).optional();
    }

    public boolean emailTaken(String email, Integer excludeId) {
        String sql = "SELECT COUNT(*) FROM customer WHERE LOWER(email) = LOWER(?)" + (excludeId != null ? " AND customer_id <> ?" : "");
        var spec = jdbc.sql(sql).param(email);
        if (excludeId != null) spec = spec.param(excludeId);
        return spec.query(Long.class).single() > 0;
    }

    public Customer create(CustomerInput in) {
        int id = ids.next("customer");
        java.sql.Timestamp now = Cols.ts(Instant.now());
        jdbc.sql("INSERT INTO customer (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'BLUE', 0, ?, ?, ?, ?)")
            .params(id, in.firstName(), in.lastName(), in.email(), in.phone(),
                in.dateOfBirth() == null ? null : java.sql.Date.valueOf(in.dateOfBirth()), in.nationality(),
                in.segment() == null ? "STANDARD" : in.segment(),
                in.marketingOptIn() != null && in.marketingOptIn(),
                in.status() == null ? "ACTIVE" : in.status(), now, now)
            .update();
        return find(id).orElseThrow();
    }

    /** PUT semantics: replaces every editable field; omitted optional fields fall back to their defaults. */
    public boolean update(int id, CustomerInput in) {
        return jdbc.sql("UPDATE customer SET first_name = ?, last_name = ?, email = ?, phone = ?, date_of_birth = ?, "
                + "nationality = ?, segment = ?, marketing_opt_in = ?, status = ?, updated_at = ? WHERE customer_id = ?")
            .params(in.firstName(), in.lastName(), in.email(), in.phone(),
                in.dateOfBirth() == null ? null : java.sql.Date.valueOf(in.dateOfBirth()), in.nationality(),
                in.segment() == null ? "STANDARD" : in.segment(),
                in.marketingOptIn() != null && in.marketingOptIn(),
                in.status() == null ? "ACTIVE" : in.status(), Cols.ts(Instant.now()), id)
            .update() > 0;
    }

    public long orderCount(int id) {
        return jdbc.sql("SELECT COUNT(*) FROM orders WHERE customer_id = ?").param(id).query(Long.class).single();
    }

    /** Removes the customer and the rows that only make sense with it (not atomic: SQL DML is per statement). */
    public boolean delete(int id) {
        for (String t : new String[] {"address", "interaction", "loyalty_txn", "customer_score"}) {
            jdbc.sql("DELETE FROM " + t + " WHERE customer_id = ?").param(id).update();
        }
        return jdbc.sql("DELETE FROM customer WHERE customer_id = ?").param(id).update() > 0;
    }
}
