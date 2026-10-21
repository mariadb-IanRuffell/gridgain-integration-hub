-- Stored procedures for Customer 360 (migration candidates for GridGain compute jobs)
-- Errors are raised with SIGNAL and a MYSQL_ERRNO, so callers (and the parity harness) see the same numbers as the compute jobs.
DELIMITER //

-- -----------------------------------------------------------------------------
-- 1. sp_recalc_loyalty_tier: derive tier from trailing-12-month completed spend.
--    Pattern: read-aggregate + reference-data lookup + conditional update.
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_recalc_loyalty_tier//
CREATE PROCEDURE sp_recalc_loyalty_tier(IN p_customer_id INT, OUT p_new_tier VARCHAR(10))
BEGIN
    DECLARE v_spend DECIMAL(14,2);
    DECLARE v_old_tier VARCHAR(10);

    SELECT loyalty_tier INTO v_old_tier FROM customer WHERE customer_id = p_customer_id;
    IF v_old_tier IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20002, MESSAGE_TEXT = 'Customer not found';
    END IF;

    SELECT IFNULL(SUM(total_amount), 0) INTO v_spend
      FROM orders
     WHERE customer_id = p_customer_id
       AND status = 'COMPLETED'
       AND order_date >= DATE_SUB(NOW(), INTERVAL 365 DAY);

    SELECT tier INTO p_new_tier
      FROM loyalty_tier_rule
     WHERE min_spend = (SELECT MAX(min_spend) FROM loyalty_tier_rule WHERE min_spend <= v_spend);

    IF p_new_tier <> v_old_tier THEN
        UPDATE customer SET loyalty_tier = p_new_tier, updated_at = NOW() WHERE customer_id = p_customer_id;
    END IF;
END//

-- -----------------------------------------------------------------------------
-- 2. sp_customer_360: single-call read of the whole customer view.
--    Pattern: multiple result sets from many tables (the classic "360" read).
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_customer_360//
CREATE PROCEDURE sp_customer_360(IN p_customer_id INT)
BEGIN
    DECLARE v_spend_12m DECIMAL(14,2);
    DECLARE v_next_threshold DECIMAL(14,2);

    IF NOT EXISTS (SELECT 1 FROM customer WHERE customer_id = p_customer_id) THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20002, MESSAGE_TEXT = 'Customer not found';
    END IF;

    -- result set 1: profile
    SELECT c.customer_id, c.first_name, c.last_name, c.email, c.phone, c.nationality,
           c.segment, c.loyalty_tier, c.loyalty_points, c.marketing_opt_in, c.status,
           c.created_at, a.city, a.country
      FROM customer c
      LEFT JOIN address a ON a.customer_id = c.customer_id AND a.is_primary = 1
     WHERE c.customer_id = p_customer_id;

    -- result set 2: order summary
    SELECT COUNT(*)                       AS order_count,
           IFNULL(SUM(total_amount), 0)   AS lifetime_spend,
           IFNULL(AVG(total_amount), 0)   AS avg_order_value,
           MAX(order_date)                AS last_order_date,
           IFNULL(SUM(CASE WHEN order_date >= DATE_SUB(NOW(), INTERVAL 365 DAY)
                           THEN total_amount ELSE 0 END), 0) AS spend_12m
      FROM orders
     WHERE customer_id = p_customer_id AND status = 'COMPLETED';

    -- result set 3: five most recent orders
    SELECT order_id, order_date, status, channel, total_amount, currency
      FROM orders
     WHERE customer_id = p_customer_id
     ORDER BY order_date DESC, order_id DESC      -- tie-break: keeps results deterministic
     LIMIT 5;

    -- result set 4: top three categories by spend
    SELECT p.category, SUM(oi.line_total) AS spend, SUM(oi.qty) AS qty
      FROM orders o
      JOIN order_item oi ON oi.order_id = o.order_id
      JOIN product p     ON p.product_id = oi.product_id
     WHERE o.customer_id = p_customer_id AND o.status = 'COMPLETED'
     GROUP BY p.category
     ORDER BY SUM(oi.line_total) DESC, p.category   -- tie-break: keeps results deterministic
     LIMIT 3;

    -- result set 5: service interactions
    SELECT COUNT(*)                                          AS total_interactions,
           IFNULL(SUM(CASE WHEN status = 'OPEN' THEN 1 ELSE 0 END), 0) AS open_cases,
           MAX(opened_at)                                    AS last_contact,
           IFNULL(AVG(sentiment), 0)                         AS avg_sentiment
      FROM interaction
     WHERE customer_id = p_customer_id;

    -- result set 6: loyalty position and distance to next tier
    SELECT IFNULL(SUM(total_amount), 0) INTO v_spend_12m
      FROM orders
     WHERE customer_id = p_customer_id AND status = 'COMPLETED'
       AND order_date >= DATE_SUB(NOW(), INTERVAL 365 DAY);
    SELECT MIN(min_spend) INTO v_next_threshold FROM loyalty_tier_rule WHERE min_spend > v_spend_12m;

    SELECT c.loyalty_tier, c.loyalty_points, v_spend_12m AS spend_12m,
           (SELECT tier FROM loyalty_tier_rule WHERE min_spend = v_next_threshold) AS next_tier,
           v_next_threshold - v_spend_12m AS spend_to_next_tier
      FROM customer c
     WHERE c.customer_id = p_customer_id;
END//

-- -----------------------------------------------------------------------------
-- 3-5. Order flow: create -> add items (stock check) -> complete (payment, points, tier).
--      Pattern: multi-statement transactions, optimistic stock decrement,
--      procedure-to-procedure call, error signalling with SIGNAL.
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_order//
CREATE PROCEDURE sp_create_order(IN p_customer_id INT, IN p_channel VARCHAR(10), OUT p_order_id INT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM customer WHERE customer_id = p_customer_id AND status = 'ACTIVE') THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20002, MESSAGE_TEXT = 'Customer not found or inactive';
    END IF;

    INSERT INTO orders (customer_id, order_date, status, channel, total_amount, currency)
    VALUES (p_customer_id, NOW(), 'NEW', IFNULL(p_channel, 'WEB'), 0, 'AED');

    SET p_order_id = LAST_INSERT_ID();
END//

DROP PROCEDURE IF EXISTS sp_add_order_item//
CREATE PROCEDURE sp_add_order_item(IN p_order_id INT, IN p_product_id INT, IN p_qty INT)
BEGIN
    DECLARE v_price DECIMAL(10,2);

    IF p_qty <= 0 THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20004, MESSAGE_TEXT = 'Quantity must be positive';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM orders WHERE order_id = p_order_id AND status = 'NEW') THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20005, MESSAGE_TEXT = 'Order not found or not open';
    END IF;

    SELECT unit_price INTO v_price FROM product WHERE product_id = p_product_id AND active = 1;
    IF v_price IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20006, MESSAGE_TEXT = 'Product not found or inactive';
    END IF;

    START TRANSACTION;
    BEGIN
        DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

        UPDATE product SET stock_qty = stock_qty - p_qty
         WHERE product_id = p_product_id AND stock_qty >= p_qty;

        IF ROW_COUNT() = 0 THEN
            SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20003, MESSAGE_TEXT = 'Insufficient stock';
        END IF;

        INSERT INTO order_item (order_id, product_id, qty, unit_price, line_total)
        VALUES (p_order_id, p_product_id, p_qty, v_price, v_price * p_qty);

        UPDATE orders SET total_amount = total_amount + (v_price * p_qty)
         WHERE order_id = p_order_id;
    END;
    COMMIT;
END//

DROP PROCEDURE IF EXISTS sp_complete_order//
CREATE PROCEDURE sp_complete_order(IN p_order_id INT, IN p_payment_method VARCHAR(10))
BEGIN
    DECLARE v_customer_id INT;
    DECLARE v_total DECIMAL(12,2);
    DECLARE v_tier VARCHAR(10);
    DECLARE v_mult DECIMAL(4,2);
    DECLARE v_points INT;
    DECLARE v_new_tier VARCHAR(10);

    SELECT customer_id, total_amount INTO v_customer_id, v_total
      FROM orders WHERE order_id = p_order_id AND status = 'NEW';

    IF v_customer_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20005, MESSAGE_TEXT = 'Order not found or not open';
    END IF;
    IF v_total <= 0 THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO = 20007, MESSAGE_TEXT = 'Order has no items';
    END IF;

    SELECT loyalty_tier INTO v_tier FROM customer WHERE customer_id = v_customer_id;
    SELECT points_multiplier INTO v_mult FROM loyalty_tier_rule WHERE tier = v_tier;
    SET v_points = FLOOR(v_total * v_mult);       -- truncate, like the compute job (CAST would round)

    START TRANSACTION;
    BEGIN
        DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

        UPDATE orders SET status = 'COMPLETED' WHERE order_id = p_order_id;

        INSERT INTO payment (order_id, method, amount, status, paid_at)
        VALUES (p_order_id, p_payment_method, v_total, 'CAPTURED', NOW());

        INSERT INTO loyalty_txn (customer_id, order_id, points, reason, txn_date)
        VALUES (v_customer_id, p_order_id, v_points, 'PURCHASE', NOW());

        UPDATE customer SET loyalty_points = loyalty_points + v_points, updated_at = NOW()
         WHERE customer_id = v_customer_id;
    END;
    COMMIT;

    CALL sp_recalc_loyalty_tier(v_customer_id, v_new_tier);
END//

-- -----------------------------------------------------------------------------
-- 6. sp_score_customers: batch churn scoring with a cursor and upsert.
--    Pattern: row-by-row batch over all customers (ideal for distributed compute,
--    colocated by customer_id).
--    Score 0-100: recency (0-60) + 12m frequency (0-30) + open cases (0-10) + negative sentiment (10)
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_score_customers//
CREATE PROCEDURE sp_score_customers(IN p_as_of DATETIME, IN p_customer_id INT)
BEGIN
    DECLARE v_cid INT;
    DECLARE v_last DATETIME;
    DECLARE v_orders_12m INT;
    DECLARE v_open_cases INT;
    DECLARE v_avg_sent DOUBLE;
    DECLARE v_recency INT;
    DECLARE v_score INT;
    DECLARE v_band VARCHAR(10);
    DECLARE v_done INT DEFAULT 0;
    DECLARE cust_cur CURSOR FOR
        SELECT customer_id FROM customer
         WHERE status = 'ACTIVE' AND (p_customer_id IS NULL OR customer_id = p_customer_id);
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    IF p_as_of IS NULL THEN SET p_as_of = NOW(); END IF;

    OPEN cust_cur;
    score_loop: LOOP
        FETCH cust_cur INTO v_cid;
        IF v_done = 1 THEN LEAVE score_loop; END IF;

        -- aggregates without GROUP BY always return one row, so NOT FOUND only ever ends the cursor
        SELECT MAX(order_date),
               IFNULL(SUM(CASE WHEN order_date >= DATE_SUB(p_as_of, INTERVAL 365 DAY) THEN 1 ELSE 0 END), 0)
          INTO v_last, v_orders_12m
          FROM orders
         WHERE customer_id = v_cid AND status = 'COMPLETED' AND order_date <= p_as_of;

        SELECT COUNT(*) INTO v_open_cases FROM interaction
         WHERE customer_id = v_cid AND status = 'OPEN';

        SELECT IFNULL(AVG(sentiment), 0) INTO v_avg_sent
          FROM interaction
         WHERE customer_id = v_cid AND opened_at >= DATE_SUB(p_as_of, INTERVAL 365 DAY);

        -- DATEDIFF counts calendar-day boundaries crossed (the job does the same with local dates)
        SET v_recency = CASE WHEN v_last IS NULL THEN 9999 ELSE DATEDIFF(p_as_of, v_last) END;

        SET v_score =
              (CASE WHEN v_recency <= 90  THEN 0
                    WHEN v_recency <= 180 THEN 20
                    WHEN v_recency <= 365 THEN 40 ELSE 60 END)
            + (CASE WHEN v_orders_12m >= 4 THEN 0
                    WHEN v_orders_12m >= 2 THEN 10
                    WHEN v_orders_12m = 1  THEN 20 ELSE 30 END)
            + (CASE WHEN v_open_cases * 5 > 10 THEN 10 ELSE v_open_cases * 5 END)
            + (CASE WHEN v_avg_sent < 0 THEN 10 ELSE 0 END);
        IF v_score > 100 THEN SET v_score = 100; END IF;

        SET v_band = CASE WHEN v_score < 30 THEN 'LOW' WHEN v_score < 60 THEN 'MEDIUM' ELSE 'HIGH' END;

        INSERT INTO customer_score (customer_id, churn_score, risk_band, scored_at)
        VALUES (v_cid, v_score, v_band, p_as_of)
        ON DUPLICATE KEY UPDATE churn_score = v_score, risk_band = v_band, scored_at = p_as_of;
    END LOOP;
    CLOSE cust_cur;

    -- summary result set (integer average, like the compute job)
    SELECT risk_band, COUNT(*) AS customers, SUM(churn_score) DIV COUNT(*) AS avg_score
      FROM customer_score
     WHERE scored_at = p_as_of
     GROUP BY risk_band
     ORDER BY risk_band;
END//

DELIMITER ;
