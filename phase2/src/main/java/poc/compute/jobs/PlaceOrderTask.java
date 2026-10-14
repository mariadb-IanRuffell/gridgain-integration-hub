package poc.compute.jobs;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.IgniteAtomicSequence;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionConcurrency;
import org.apache.ignite.transactions.TransactionIsolation;

/**
 * Replaces sp_create_order + sp_add_order_item + sp_complete_order (three round trips, three transactions)
 * with ONE job and ONE cache transaction on the customer's owning node.
 *
 * Argument map: customer_id (Integer), channel (String), payment_method (String),
 *               items (List of Integer: product_id, qty, product_id, qty, ...).
 *
 * Differences from the procedures, both deliberate:
 *  - All-or-nothing: if the 2nd item is out of stock nothing is changed (the T-SQL flow left a NEW order
 *    with the 1st item already decremented).
 *  - New ids come from GridGain sequences starting at 1,000,000,000, so they can never collide with
 *    Sybase identity values that CDC is still replicating.
 */
public class PlaceOrderTask extends AffinityTask<Map<String, Object>> {
    @Override
    int customerId(Map<String, Object> arg) {
        return (Integer) arg.get("customer_id");
    }

    @Override
    ComputeJob job(Map<String, Object> arg) {
        List<?> flat = (List<?>) arg.get("items");
        int[] pids = new int[flat.size() / 2], qtys = new int[flat.size() / 2];
        for (int i = 0; i < pids.length; i++) {
            pids[i] = ((Number) flat.get(2 * i)).intValue();
            qtys[i] = ((Number) flat.get(2 * i + 1)).intValue();
        }
        return new Job((Integer) arg.get("customer_id"), (String) arg.getOrDefault("channel", "WEB"),
            (String) arg.getOrDefault("payment_method", "CARD"), pids, qtys);
    }

    static class Job extends CustomerJob {
        private static final long ID_BASE = 1_000_000_000L;

        private final int cid;
        private final String channel, method;
        private final int[] pids, qtys;

        Job(int cid, String channel, String method, int[] pids, int[] qtys) {
            this.cid = cid;
            this.channel = channel;
            this.method = method;
            this.pids = pids;
            this.qtys = qtys;
        }

        @Override
        public Object execute() {
            if (pids.length == 0) return fail(-7, 20007, "Order has no items");

            Timestamp now = new Timestamp(System.currentTimeMillis());
            IgniteCache<Integer, BinaryObject> customers = ignite.<Integer, BinaryObject>cache("customer").withKeepBinary();
            IgniteCache<Integer, BinaryObject> products = ignite.<Integer, BinaryObject>cache("product").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> orders = ignite.<BinaryObject, BinaryObject>cache("orders").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> items = ignite.<BinaryObject, BinaryObject>cache("order_item").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> payments = ignite.<BinaryObject, BinaryObject>cache("payment").withKeepBinary();
            IgniteCache<BinaryObject, BinaryObject> txns = ignite.<BinaryObject, BinaryObject>cache("loyalty_txn").withKeepBinary();

            // Ids are allocated BEFORE the transaction: the first use of an atomic sequence starts a system
            // cache, which is not allowed inside a transaction. Unused ids after a rollback are just gaps.
            for (int q : qtys) if (q <= 0) return fail(-4, 20004, "Quantity must be positive");
            int orderId = (int) ignite.atomicSequence("seq_order_id", ID_BASE, true).incrementAndGet();
            IgniteAtomicSequence itemSeq = ignite.atomicSequence("seq_order_item_id", ID_BASE, true);
            int[] itemIds = new int[pids.length];
            for (int i = 0; i < itemIds.length; i++) itemIds[i] = (int) itemSeq.incrementAndGet();
            int payId = (int) ignite.atomicSequence("seq_payment_id", ID_BASE, true).incrementAndGet();
            int txnId = (int) ignite.atomicSequence("seq_loyalty_txn_id", ID_BASE, true).incrementAndGet();

            try (Transaction tx = ignite.transactions().txStart(
                    TransactionConcurrency.PESSIMISTIC, TransactionIsolation.REPEATABLE_READ, 10_000, 0)) {

                // sp_create_order: customer must exist and be ACTIVE (the get() also locks the customer row)
                BinaryObject cust = customers.get(cid);
                if (cust == null || !"ACTIVE".equals(cust.<String>field("STATUS")))
                    return fail(-1, 20002, "Customer not found or inactive");

                // sp_add_order_item, per item and in order: qty, product, stock
                Map<Integer, BinaryObject> prod = new HashMap<>();
                Map<Integer, Integer> stock = new HashMap<>();
                BigDecimal total = BigDecimal.ZERO;
                BigDecimal[] price = new BigDecimal[pids.length];
                for (int i = 0; i < pids.length; i++) {
                    if (qtys[i] <= 0) return fail(-4, 20004, "Quantity must be positive");
                    BinaryObject p = prod.get(pids[i]);
                    if (p == null) {
                        p = products.get(pids[i]);
                        if (p == null || !Boolean.TRUE.equals(p.<Boolean>field("ACTIVE")))
                            return fail(-6, 20006, "Product not found or inactive");
                        prod.put(pids[i], p);
                        stock.put(pids[i], p.<Integer>field("STOCK_QTY"));
                    }
                    if (stock.get(pids[i]) < qtys[i]) return fail(-3, 20003, "Insufficient stock");
                    stock.put(pids[i], stock.get(pids[i]) - qtys[i]);
                    price[i] = p.<BigDecimal>field("UNIT_PRICE");
                    total = total.add(price[i].multiply(BigDecimal.valueOf(qtys[i])));
                }

                // sp_complete_order: points use the tier held BEFORE this order, then the tier is recalculated
                String tierBefore = cust.<String>field("LOYALTY_TIER");
                int points = total.multiply(Loyalty.multiplier(ignite, tierBefore)).intValue();
                BigDecimal spend = Loyalty.spend12m(ignite, cid, now).add(total);
                String tierAfter = Loyalty.tierForSpend(ignite, spend);

                orders.put(key("OrdersKey", "ORDER_ID", orderId), ignite.binary().builder("Orders")
                    .setField("ORDER_DATE", now).setField("STATUS", "COMPLETED").setField("CHANNEL", channel)
                    .setField("TOTAL_AMOUNT", total).setField("CURRENCY", "AED").build());

                for (int i = 0; i < pids.length; i++) {
                    items.put(key("OrderItemKey", "ORDER_ITEM_ID", itemIds[i]),
                        ignite.binary().builder("OrderItem")
                            .setField("ORDER_ID", orderId).setField("PRODUCT_ID", pids[i]).setField("QTY", qtys[i])
                            .setField("UNIT_PRICE", price[i])
                            .setField("LINE_TOTAL", price[i].multiply(BigDecimal.valueOf(qtys[i]))).build());
                }

                for (Map.Entry<Integer, BinaryObject> e : prod.entrySet())
                    products.put(e.getKey(), e.getValue().toBuilder().setField("STOCK_QTY", stock.get(e.getKey())).build());

                payments.put(key("PaymentKey", "PAYMENT_ID", payId),
                    ignite.binary().builder("Payment").setField("ORDER_ID", orderId).setField("METHOD", method)
                        .setField("AMOUNT", total).setField("STATUS", "CAPTURED").setField("PAID_AT", now).build());

                txns.put(key("LoyaltyTxnKey", "TXN_ID", txnId),
                    ignite.binary().builder("LoyaltyTxn").setField("ORDER_ID", orderId).setField("POINTS", points)
                        .setField("REASON", "PURCHASE").setField("TXN_DATE", now).build());

                int newPoints = cust.<Integer>field("LOYALTY_POINTS") + points;
                customers.put(cid, cust.toBuilder().setField("LOYALTY_POINTS", newPoints)
                    .setField("LOYALTY_TIER", tierAfter).setField("UPDATED_AT", now).build());

                tx.commit();

                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                out.put("rc", 0);
                out.put("order_id", orderId);
                out.put("customer_id", cid);
                out.put("total_amount", total);
                out.put("points", points);
                out.put("tier_before", tierBefore);
                out.put("tier_after", tierAfter);
                out.put("loyalty_points", newPoints);
                return out;
            }
        }

        /** Composite SQL primary key (surrogate id + customer_id affinity key) as a BinaryObject. */
        private BinaryObject key(String type, String idField, int id) {
            return ignite.binary().builder(type).setField(idField, id).setField("CUSTOMER_ID", cid).build();
        }
    }
}
