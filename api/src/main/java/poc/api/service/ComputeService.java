package poc.api.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.IgniteException;
import org.apache.ignite.client.ClientException;
import org.apache.ignite.client.IgniteClient;
import org.springframework.stereotype.Service;
import poc.api.model.Dto.PlaceOrderRequest;
import poc.api.model.Dto.PlaceOrderResult;
import poc.api.web.ApiExceptions.BadRequest;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;
import poc.api.web.ApiExceptions.Unavailable;

/**
 * Calls the GridGain compute jobs (poc.compute.jobs.*, deployed on every node) that replaced the Sybase
 * stored procedures. Jobs report business errors as {rc, error_no, error}, using the same numbers the
 * stored procedures raised (20002 not found, 20003 insufficient stock, ...); those are mapped to HTTP here.
 */
@Service
public class ComputeService {
    private static final String PKG = "poc.compute.jobs.";

    private final IgniteClient client;

    ComputeService(IgniteClient client) {
        this.client = client;
    }

    @SuppressWarnings("unchecked")
    private <R> R run(String task, Object arg) {
        try {
            return (R) client.compute().withTimeout(60_000).execute(PKG + task, arg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while waiting for GridGain compute", e);
        } catch (ClientException | IgniteException e) {
            throw new Unavailable("GridGain compute call failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> checked(Map<String, Object> result, String what, Object id) {
        if (((Number) result.get("rc")).intValue() == 0) return result;
        String message = String.valueOf(result.get("error"));
        switch (((Number) result.get("error_no")).intValue()) {
            case 20002: throw new NotFound(what, id);
            case 20003: throw new Conflict(message);
            default: throw new BadRequest(message);
        }
    }

    /** Replaces sp_customer_360: profile, order summary, recent orders, top categories, interactions, loyalty. */
    public Map<String, Object> customer360(int customerId) {
        Map<String, Object> r = run("Customer360Task", customerId);
        checked(r, "Customer", customerId);
        r.remove("rc");
        return r;
    }

    /** Replaces sp_recalc_loyalty_tier. */
    public Map<String, Object> recalculateTier(int customerId) {
        Map<String, Object> r = run("RecalcLoyaltyTierTask", customerId);
        checked(r, "Customer", customerId);
        r.remove("rc");
        return r;
    }

    /** Replaces sp_score_customers: one job per node scores the customers that node owns. */
    public List<Object> scoreCustomers(Long asOfMillis) {
        return run("ScoreCustomersTask", asOfMillis);
    }

    /** Replaces sp_create_order + sp_add_order_item + sp_complete_order, as one transaction. */
    public PlaceOrderResult placeOrder(PlaceOrderRequest req) {
        List<Object> flat = new ArrayList<>();
        req.items().forEach(l -> {
            flat.add(l.productId());
            flat.add(l.quantity());
        });
        Map<String, Object> arg = new HashMap<>();
        arg.put("customer_id", req.customerId());
        arg.put("channel", req.channel() == null ? "WEB" : req.channel());
        arg.put("payment_method", req.paymentMethod() == null ? "CARD" : req.paymentMethod());
        arg.put("items", flat);

        Map<String, Object> r = run("PlaceOrderTask", arg);
        checked(r, "Customer", req.customerId());
        return new PlaceOrderResult((Integer) r.get("order_id"), (Integer) r.get("customer_id"),
            (BigDecimal) r.get("total_amount"), (Integer) r.get("points"), (String) r.get("tier_before"),
            (String) r.get("tier_after"), (Integer) r.get("loyalty_points"));
    }
}
