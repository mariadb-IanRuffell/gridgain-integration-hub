-- Cross-source SQL on the GridGain hub: Sybase tables (customer, orders, interaction, loyalty_*) joined with Oracle tables
-- (booking, booking_segment, flight, airport). The join key is customer_id. Customer-owned tables from both systems are colocated
-- on customer_id, so these joins run on the node that owns each customer, with no data movement.
-- Connect (DBeaver): jdbc:ignite:thin://127.0.0.1:10800,127.0.0.1:10801,127.0.0.1:10802   (no user / password)
-- Run all of them:  ./run.sh

-- 1. Lifetime value: retail spend (Sybase) + travel spend (Oracle) per customer
SELECT c.customer_id, c.last_name, c.loyalty_tier,
       COALESCE(o.retail_spend, 0) AS retail_spend,
       COALESCE(t.travel_spend, 0) AS travel_spend,
       COALESCE(o.retail_spend, 0) + COALESCE(t.travel_spend, 0) AS lifetime_value
FROM customer c
LEFT JOIN (SELECT customer_id, SUM(total_amount) AS retail_spend FROM orders WHERE status = 'COMPLETED' GROUP BY customer_id) o
       ON o.customer_id = c.customer_id
LEFT JOIN (SELECT customer_id, SUM(fare) AS travel_spend FROM booking_segment WHERE status <> 'CANCELLED' GROUP BY customer_id) t
       ON t.customer_id = c.customer_id
ORDER BY lifetime_value DESC
LIMIT 10;

-- 2. Where the money comes from, by loyalty tier (a Sybase attribute) across both systems
SELECT c.loyalty_tier,
       COUNT(*) AS customers,
       SUM(COALESCE(o.retail_spend, 0)) AS retail_total,
       SUM(COALESCE(t.travel_spend, 0)) AS travel_total,
       CAST(SUM(COALESCE(t.travel_spend, 0)) * 100.0 / NULLIF(SUM(COALESCE(o.retail_spend, 0)) + SUM(COALESCE(t.travel_spend, 0)), 0) AS DECIMAL(5,1)) AS travel_pct
FROM customer c
LEFT JOIN (SELECT customer_id, SUM(total_amount) AS retail_spend FROM orders WHERE status = 'COMPLETED' GROUP BY customer_id) o
       ON o.customer_id = c.customer_id
LEFT JOIN (SELECT customer_id, SUM(fare) AS travel_spend FROM booking_segment WHERE status <> 'CANCELLED' GROUP BY customer_id) t
       ON t.customer_id = c.customer_id
GROUP BY c.loyalty_tier
ORDER BY SUM(COALESCE(o.retail_spend, 0)) + SUM(COALESCE(t.travel_spend, 0)) DESC;

-- 3. Proactive service: customers with a recent negative interaction (Sybase) who are booked on a delayed or cancelled upcoming flight (Oracle)
SELECT DISTINCT c.customer_id, c.last_name, c.loyalty_tier, i.type AS interaction_type, i.subject,
       f.flight_no, f.origin, f.destination, f.dep_time, f.status AS flight_status, f.delay_minutes
FROM interaction i
JOIN customer c        ON c.customer_id = i.customer_id
JOIN booking_segment s ON s.customer_id = i.customer_id AND s.status IN ('TICKETED', 'CHECKED_IN')
JOIN flight f          ON f.flight_id = s.flight_id AND f.status IN ('DELAYED', 'CANCELLED') AND f.dep_time > CURRENT_TIMESTAMP
WHERE i.sentiment < 0
ORDER BY f.dep_time, c.customer_id
LIMIT 15;

-- 4. Top routes for GOLD and PLATINUM customers (tier from Sybase, routes from Oracle)
SELECT f.origin, f.destination, COUNT(*) AS segments, SUM(s.fare) AS fare_total
FROM booking_segment s
JOIN customer c ON c.customer_id = s.customer_id
JOIN flight f   ON f.flight_id = s.flight_id
WHERE c.loyalty_tier IN ('GOLD', 'PLATINUM') AND s.status <> 'CANCELLED'
GROUP BY f.origin, f.destination
ORDER BY fare_total DESC
LIMIT 10;

-- 5. Cross-sell list: big retail spenders (Sybase) who have never booked a flight (Oracle)
SELECT c.customer_id, c.last_name, c.loyalty_tier, c.email, o.retail_spend
FROM customer c
JOIN (SELECT customer_id, SUM(total_amount) AS retail_spend FROM orders WHERE status = 'COMPLETED' GROUP BY customer_id) o
  ON o.customer_id = c.customer_id
WHERE c.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.customer_id = c.customer_id)
ORDER BY o.retail_spend DESC
LIMIT 10;

-- 6. Disruption cost by tier: compensation owed (Oracle) against how loyal the affected customers are (Sybase)
SELECT c.loyalty_tier, COUNT(*) AS compensated_segments, SUM(k.amount) AS compensation_total
FROM compensation k
JOIN customer c ON c.customer_id = k.customer_id
GROUP BY c.loyalty_tier
ORDER BY compensation_total DESC;
