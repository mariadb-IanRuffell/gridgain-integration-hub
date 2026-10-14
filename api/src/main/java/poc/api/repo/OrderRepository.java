package poc.api.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import poc.api.model.Dto.Order;
import poc.api.model.Dto.OrderItem;
import poc.api.model.Dto.Page;
import poc.api.model.Dto.Payment;

/** Read access plus the few direct writes allowed on orders. New orders are created by the place-order job. */
@Repository
public class OrderRepository {
    private static final String COLS = "order_id, customer_id, order_date, status, channel, total_amount, currency";

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Order map(ResultSet rs, int row) throws SQLException {
        Cols c = new Cols(rs);
        return new Order(c.integer(), c.integer(), c.instant(), c.string(), c.string(), c.decimal(), c.string(), null, null);
    }

    public Page<Order> list(Integer customerId, String status, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (customerId != null) { where.append(" AND customer_id = ?"); args.add(customerId); }
        if (status != null) { where.append(" AND status = ?"); args.add(status); }
        long total = jdbc.sql("SELECT COUNT(*) FROM orders" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Order> items = jdbc.sql("SELECT " + COLS + " FROM orders" + where
                + " ORDER BY order_date DESC, order_id DESC LIMIT ? OFFSET ?")
            .params(pageArgs).query(OrderRepository::map).list();
        return new Page<>(items, limit, offset, total);
    }

    public Optional<Order> find(int orderId) {
        return jdbc.sql("SELECT " + COLS + " FROM orders WHERE order_id = ?").param(orderId)
            .query(OrderRepository::map).optional();
    }

    /** Order with its line items and payments (queries are pruned to the customer's partition). */
    public Optional<Order> findDetail(int orderId) {
        return find(orderId).map(o -> new Order(o.orderId(), o.customerId(), o.orderDate(), o.status(), o.channel(),
            o.totalAmount(), o.currency(), items(orderId, o.customerId()), payments(orderId, o.customerId())));
    }

    private List<OrderItem> items(int orderId, int customerId) {
        return jdbc.sql("SELECT order_item_id, order_id, product_id, qty, unit_price, line_total FROM order_item "
                + "WHERE customer_id = ? AND order_id = ? ORDER BY order_item_id")
            .params(customerId, orderId).query((rs, n) -> {
                Cols c = new Cols(rs);
                return new OrderItem(c.integer(), c.integer(), c.integer(), c.integer(), c.decimal(), c.decimal());
            }).list();
    }

    private List<Payment> payments(int orderId, int customerId) {
        return jdbc.sql("SELECT payment_id, order_id, method, amount, status, paid_at FROM payment "
                + "WHERE customer_id = ? AND order_id = ? ORDER BY payment_id")
            .params(customerId, orderId).query((rs, n) -> {
                Cols c = new Cols(rs);
                return new Payment(c.integer(), c.integer(), c.string(), c.decimal(), c.string(), c.instant());
            }).list();
    }

    public boolean updateChannel(int orderId, int customerId, String channel) {
        return jdbc.sql("UPDATE orders SET channel = ? WHERE order_id = ? AND customer_id = ?")
            .params(channel, orderId, customerId).update() > 0;
    }

    public boolean delete(int orderId, int customerId) {
        jdbc.sql("DELETE FROM payment WHERE order_id = ? AND customer_id = ?").params(orderId, customerId).update();
        jdbc.sql("DELETE FROM order_item WHERE order_id = ? AND customer_id = ?").params(orderId, customerId).update();
        return jdbc.sql("DELETE FROM orders WHERE order_id = ? AND customer_id = ?").params(orderId, customerId).update() > 0;
    }
}
