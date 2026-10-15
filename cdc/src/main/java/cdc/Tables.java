package cdc;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The replicated tables. Keep in sync with sql/01_schema.sql and gridgain-schema-sybase.sql. */
final class Tables {
    static final List<TableSpec> ALL = List.of(
        TableSpec.plain("customer", "customer_id", "customer_id", "first_name", "last_name", "email", "phone",
            "date_of_birth", "nationality", "segment", "loyalty_tier", "loyalty_points", "marketing_opt_in",
            "status", "created_at", "updated_at"),
        TableSpec.plain("address", "address_id", "address_id", "customer_id", "addr_type", "line1", "city",
            "postcode", "country", "is_primary"),
        TableSpec.plain("product", "product_id", "product_id", "sku", "name", "category", "unit_price",
            "stock_qty", "active"),
        TableSpec.plain("orders", "order_id", "order_id", "customer_id", "order_date", "status", "channel",
            "total_amount", "currency"),
        TableSpec.viaOrders("order_item", "order_item_id", "order_item_id", "order_id", "product_id", "qty",
            "unit_price", "line_total"),
        TableSpec.viaOrders("payment", "payment_id", "payment_id", "order_id", "method", "amount", "status",
            "paid_at"),
        TableSpec.plain("interaction", "interaction_id", "interaction_id", "customer_id", "channel", "type",
            "subject", "sentiment", "status", "opened_at", "closed_at"),
        TableSpec.plain("loyalty_txn", "txn_id", "txn_id", "customer_id", "order_id", "points", "reason",
            "txn_date"),
        TableSpec.plain("customer_score", "customer_id", "customer_id", "churn_score", "risk_band", "scored_at"),
        TableSpec.plain("loyalty_tier_rule", "tier", true, "tier", "min_spend", "points_multiplier"));

    static final Map<String, TableSpec> BY_NAME =
        ALL.stream().collect(Collectors.toMap(t -> t.name, Function.identity()));

    private Tables() {}
}
