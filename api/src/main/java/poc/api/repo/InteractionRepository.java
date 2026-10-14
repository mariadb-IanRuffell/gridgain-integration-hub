package poc.api.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import poc.api.model.Dto.Interaction;
import poc.api.model.Dto.InteractionInput;
import poc.api.model.Dto.Page;

@Repository
public class InteractionRepository {
    private static final String COLS = "interaction_id, customer_id, channel, type, subject, sentiment, status, opened_at, closed_at";

    private final JdbcClient jdbc;
    private final Ids ids;

    InteractionRepository(JdbcClient jdbc, Ids ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    private static Interaction map(ResultSet rs, int row) throws SQLException {
        Cols c = new Cols(rs);
        return new Interaction(c.integer(), c.integer(), c.string(), c.string(), c.string(), c.integer(), c.string(),
            c.instant(), c.instant());
    }

    public Page<Interaction> list(int customerId, String status, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE customer_id = ?");
        List<Object> args = new ArrayList<>(List.of(customerId));
        if (status != null) { where.append(" AND status = ?"); args.add(status); }
        long total = jdbc.sql("SELECT COUNT(*) FROM interaction" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Interaction> items = jdbc.sql("SELECT " + COLS + " FROM interaction" + where
                + " ORDER BY opened_at DESC, interaction_id DESC LIMIT ? OFFSET ?")
            .params(pageArgs).query(InteractionRepository::map).list();
        return new Page<>(items, limit, offset, total);
    }

    public Optional<Interaction> find(int customerId, int id) {
        return jdbc.sql("SELECT " + COLS + " FROM interaction WHERE customer_id = ? AND interaction_id = ?")
            .params(customerId, id).query(InteractionRepository::map).optional();
    }

    public Interaction create(int customerId, InteractionInput in) {
        int id = ids.next("interaction");
        Instant now = Instant.now();
        String status = in.status() == null ? "OPEN" : in.status();
        jdbc.sql("INSERT INTO interaction (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(id, customerId, in.channel(), in.type(), in.subject(), in.sentiment() == null ? 0 : in.sentiment(),
                status, Cols.ts(now), "CLOSED".equals(status) ? Cols.ts(now) : null).update();
        return find(customerId, id).orElseThrow();
    }

    public boolean update(int customerId, int id, InteractionInput in, Interaction existing) {
        String status = in.status() == null ? existing.status() : in.status();
        // closing stamps closedAt once; re-opening clears it
        Instant closedAt = "CLOSED".equals(status) ? (existing.closedAt() != null ? existing.closedAt() : Instant.now()) : null;
        return jdbc.sql("UPDATE interaction SET channel = ?, type = ?, subject = ?, sentiment = ?, status = ?, closed_at = ? "
                + "WHERE customer_id = ? AND interaction_id = ?")
            .params(in.channel(), in.type(), in.subject(), in.sentiment() == null ? 0 : in.sentiment(), status,
                Cols.ts(closedAt), customerId, id).update() > 0;
    }

    public boolean delete(int customerId, int id) {
        return jdbc.sql("DELETE FROM interaction WHERE customer_id = ? AND interaction_id = ?")
            .params(customerId, id).update() > 0;
    }
}
