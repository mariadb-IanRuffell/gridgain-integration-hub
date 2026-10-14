package poc.api.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import poc.api.model.Dto.Page;
import poc.api.model.Dto.Product;
import poc.api.model.Dto.ProductInput;

@Repository
public class ProductRepository {
    private static final String COLS = "product_id, sku, name, category, unit_price, stock_qty, active";

    private final JdbcClient jdbc;
    private final Ids ids;

    ProductRepository(JdbcClient jdbc, Ids ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    private static Product map(ResultSet rs, int row) throws SQLException {
        Cols c = new Cols(rs);
        return new Product(c.integer(), c.string(), c.string(), c.string(), c.decimal(), c.integer(), c.bool());
    }

    public Page<Product> list(String category, Boolean active, String q, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (category != null) { where.append(" AND category = ?"); args.add(category); }
        if (active != null) { where.append(" AND active = ?"); args.add(active); }
        if (q != null) { where.append(" AND LOWER(name) LIKE ?"); args.add("%" + q.toLowerCase() + "%"); }
        long total = jdbc.sql("SELECT COUNT(*) FROM product" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Product> items = jdbc.sql("SELECT " + COLS + " FROM product" + where + " ORDER BY product_id LIMIT ? OFFSET ?")
            .params(pageArgs).query(ProductRepository::map).list();
        return new Page<>(items, limit, offset, total);
    }

    public Optional<Product> find(int id) {
        return jdbc.sql("SELECT " + COLS + " FROM product WHERE product_id = ?").param(id)
            .query(ProductRepository::map).optional();
    }

    public boolean skuTaken(String sku, Integer excludeId) {
        String sql = "SELECT COUNT(*) FROM product WHERE sku = ?" + (excludeId != null ? " AND product_id <> ?" : "");
        var spec = jdbc.sql(sql).param(sku);
        if (excludeId != null) spec = spec.param(excludeId);
        return spec.query(Long.class).single() > 0;
    }

    public Product create(ProductInput in) {
        int id = ids.next("product");
        jdbc.sql("INSERT INTO product (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")
            .params(id, in.sku(), in.name(), in.category(), in.unitPrice(), in.stockQty(),
                in.active() == null || in.active()).update();
        return find(id).orElseThrow();
    }

    public boolean update(int id, ProductInput in) {
        return jdbc.sql("UPDATE product SET sku = ?, name = ?, category = ?, unit_price = ?, stock_qty = ?, active = ? "
                + "WHERE product_id = ?")
            .params(in.sku(), in.name(), in.category(), in.unitPrice(), in.stockQty(),
                in.active() == null || in.active(), id).update() > 0;
    }

    public long orderItemCount(int id) {
        return jdbc.sql("SELECT COUNT(*) FROM order_item WHERE product_id = ?").param(id).query(Long.class).single();
    }

    public boolean delete(int id) {
        return jdbc.sql("DELETE FROM product WHERE product_id = ?").param(id).update() > 0;
    }
}
