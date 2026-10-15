-- GridGain 8.9 target schema for the Sybase Customer 360 data.
-- Everything customer-owned is colocated on customer_id so that Phase 2 compute jobs
-- (sp_customer_360, sp_score_customers, order flow) run next to their data.
-- order_item and payment carry a denormalised customer_id (resolved by the CDC reader via orders).
-- product and loyalty_tier_rule are small reference tables: replicated to every node.
-- All tables are TRANSACTIONAL with explicit key/value type names so Phase 2 compute jobs can
-- update several of them atomically through the key-value API (BinaryObject builders).

CREATE TABLE IF NOT EXISTS customer (
  customer_id INT PRIMARY KEY,
  first_name VARCHAR(60), last_name VARCHAR(60), email VARCHAR(120), phone VARCHAR(30),
  date_of_birth DATE, nationality CHAR(2), segment VARCHAR(20), loyalty_tier VARCHAR(10),
  loyalty_points INT, marketing_opt_in BOOLEAN, status VARCHAR(10),
  created_at TIMESTAMP, updated_at TIMESTAMP
) WITH "template=partitioned,backups=1,cache_name=customer,atomicity=transactional,value_type=Customer";

CREATE TABLE IF NOT EXISTS address (
  address_id INT, customer_id INT,
  addr_type VARCHAR(10), line1 VARCHAR(120), city VARCHAR(60), postcode VARCHAR(20),
  country CHAR(2), is_primary BOOLEAN,
  PRIMARY KEY (address_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=address,atomicity=transactional,key_type=AddressKey,value_type=Address";

CREATE TABLE IF NOT EXISTS orders (
  order_id INT, customer_id INT,
  order_date TIMESTAMP, status VARCHAR(10), channel VARCHAR(10),
  total_amount DECIMAL(12,2), currency CHAR(3),
  PRIMARY KEY (order_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=orders,atomicity=transactional,key_type=OrdersKey,value_type=Orders";

CREATE TABLE IF NOT EXISTS order_item (
  order_item_id INT, customer_id INT,
  order_id INT, product_id INT, qty INT, unit_price DECIMAL(10,2), line_total DECIMAL(12,2),
  PRIMARY KEY (order_item_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=order_item,atomicity=transactional,key_type=OrderItemKey,value_type=OrderItem";

CREATE TABLE IF NOT EXISTS payment (
  payment_id INT, customer_id INT,
  order_id INT, method VARCHAR(10), amount DECIMAL(12,2), status VARCHAR(10), paid_at TIMESTAMP,
  PRIMARY KEY (payment_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=payment,atomicity=transactional,key_type=PaymentKey,value_type=Payment";

CREATE TABLE IF NOT EXISTS interaction (
  interaction_id INT, customer_id INT,
  channel VARCHAR(10), type VARCHAR(20), subject VARCHAR(200), sentiment SMALLINT,
  status VARCHAR(10), opened_at TIMESTAMP, closed_at TIMESTAMP,
  PRIMARY KEY (interaction_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=interaction,atomicity=transactional,key_type=InteractionKey,value_type=Interaction";

CREATE TABLE IF NOT EXISTS loyalty_txn (
  txn_id INT, customer_id INT,
  order_id INT, points INT, reason VARCHAR(20), txn_date TIMESTAMP,
  PRIMARY KEY (txn_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=loyalty_txn,atomicity=transactional,key_type=LoyaltyTxnKey,value_type=LoyaltyTxn";

CREATE TABLE IF NOT EXISTS customer_score (
  customer_id INT PRIMARY KEY,
  churn_score INT, risk_band VARCHAR(10), scored_at TIMESTAMP
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=customer_score,atomicity=transactional,value_type=CustomerScore";

CREATE TABLE IF NOT EXISTS product (
  product_id INT PRIMARY KEY,
  sku VARCHAR(20), name VARCHAR(100), category VARCHAR(20),
  unit_price DECIMAL(10,2), stock_qty INT, active BOOLEAN
) WITH "template=replicated,cache_name=product,atomicity=transactional,value_type=Product";

CREATE TABLE IF NOT EXISTS loyalty_tier_rule (
  tier VARCHAR(10) PRIMARY KEY,
  min_spend DECIMAL(14,2), points_multiplier DECIMAL(4,2)
) WITH "template=replicated,cache_name=loyalty_tier_rule,atomicity=transactional,value_type=LoyaltyTierRule";
