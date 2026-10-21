SELECT '--- procs present' AS '';
SELECT routine_name FROM information_schema.routines WHERE routine_schema = DATABASE() AND routine_name LIKE 'sp\_%' ORDER BY routine_name;
SELECT '--- seeded tier vs recomputed tier (expect 0 mismatches)' AS '';
SELECT COUNT(*) AS mismatches FROM customer c
 WHERE c.loyalty_tier <> (SELECT r.tier FROM loyalty_tier_rule r WHERE r.min_spend =
        (SELECT MAX(min_spend) FROM loyalty_tier_rule
          WHERE min_spend <= (SELECT IFNULL(SUM(o.total_amount),0) FROM orders o
                               WHERE o.customer_id = c.customer_id AND o.status = 'COMPLETED'
                                 AND o.order_date >= DATE_SUB(NOW(), INTERVAL 365 DAY))));
SELECT '--- tier distribution' AS '';
SELECT loyalty_tier, COUNT(*) AS customers FROM customer GROUP BY loyalty_tier ORDER BY 2 DESC;
SELECT '--- sp_customer_360 (customer 1: six result sets)' AS '';
CALL sp_customer_360(1);
SELECT '--- order flow on an active SILVER customer: create, add 2 items, complete' AS '';
SELECT MIN(customer_id) INTO @cid FROM customer WHERE status = 'ACTIVE' AND loyalty_tier = 'SILVER';
SELECT customer_id, loyalty_tier, loyalty_points FROM customer WHERE customer_id = @cid;
CALL sp_create_order(@cid, 'APP', @oid);
CALL sp_add_order_item(@oid, 4, 2);
CALL sp_add_order_item(@oid, 17, 1);
CALL sp_complete_order(@oid, 'CARD');
SELECT o.order_id, o.status, o.total_amount, (SELECT COUNT(*) FROM order_item WHERE order_id = o.order_id) AS items,
       (SELECT SUM(amount) FROM payment WHERE order_id = o.order_id) AS paid,
       (SELECT SUM(points) FROM loyalty_txn WHERE order_id = o.order_id) AS points
  FROM orders o WHERE o.order_id = @oid;
SELECT customer_id, loyalty_tier, loyalty_points FROM customer WHERE customer_id = @cid;
SELECT '--- sp_score_customers' AS '';
CALL sp_score_customers(NULL, NULL);
SELECT c.customer_id, c.last_name, s.churn_score, s.risk_band FROM customer_score s JOIN customer c ON c.customer_id = s.customer_id ORDER BY s.churn_score DESC LIMIT 5;
