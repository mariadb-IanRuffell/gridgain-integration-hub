-- Customer 360 schema for MariaDB 11.4 (InnoDB)
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS customer_score, loyalty_txn, interaction, payment, order_item, orders, product, address, customer, loyalty_tier_rule;
SET FOREIGN_KEY_CHECKS = 1;

-- Reference data: tier thresholds are on trailing-12-month completed spend
CREATE TABLE loyalty_tier_rule (
    tier               VARCHAR(10)   NOT NULL,
    min_spend          DECIMAL(14,2) NOT NULL,
    points_multiplier  DECIMAL(4,2)  NOT NULL,
    PRIMARY KEY (tier)
) ENGINE=InnoDB;

CREATE TABLE customer (
    customer_id      INT           NOT NULL AUTO_INCREMENT,
    first_name       VARCHAR(60)   NOT NULL,
    last_name        VARCHAR(60)   NOT NULL,
    email            VARCHAR(120)  NOT NULL,
    phone            VARCHAR(30)   NULL,
    date_of_birth    DATE          NULL,
    nationality      CHAR(2)       NULL,
    segment          VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',   -- STANDARD / PREMIUM / CORPORATE
    loyalty_tier     VARCHAR(10)   NOT NULL DEFAULT 'BLUE',
    loyalty_points   INT           NOT NULL DEFAULT 0,
    marketing_opt_in BOOLEAN       NOT NULL DEFAULT 0,
    status           VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE / INACTIVE
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (customer_id),
    UNIQUE KEY uq_customer_email (email)
) ENGINE=InnoDB;

CREATE TABLE address (
    address_id   INT          NOT NULL AUTO_INCREMENT,
    customer_id  INT          NOT NULL,
    addr_type    VARCHAR(10)  NOT NULL,                           -- HOME / BILLING / WORK
    line1        VARCHAR(120) NOT NULL,
    city         VARCHAR(60)  NOT NULL,
    postcode     VARCHAR(20)  NULL,
    country      CHAR(2)      NOT NULL,
    is_primary   BOOLEAN      NOT NULL DEFAULT 0,
    PRIMARY KEY (address_id),
    KEY ix_address_customer (customer_id),
    CONSTRAINT fk_address_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
) ENGINE=InnoDB;

CREATE TABLE product (
    product_id   INT           NOT NULL AUTO_INCREMENT,
    sku          VARCHAR(20)   NOT NULL,
    name         VARCHAR(100)  NOT NULL,
    category     VARCHAR(20)   NOT NULL,       -- FLIGHT / HOTEL / LOUNGE / UPGRADE / INSURANCE / ANCILLARY
    unit_price   DECIMAL(10,2) NOT NULL,
    stock_qty    INT           NOT NULL,
    active       BOOLEAN       NOT NULL DEFAULT 1,
    PRIMARY KEY (product_id),
    UNIQUE KEY uq_product_sku (sku)
) ENGINE=InnoDB;

CREATE TABLE orders (
    order_id      INT           NOT NULL AUTO_INCREMENT,
    customer_id   INT           NOT NULL,
    order_date    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status        VARCHAR(10)   NOT NULL DEFAULT 'NEW',   -- NEW / COMPLETED / CANCELLED
    channel       VARCHAR(10)   NOT NULL DEFAULT 'WEB',   -- WEB / APP / CALL / AGENT
    total_amount  DECIMAL(12,2) NOT NULL DEFAULT 0,
    currency      CHAR(3)       NOT NULL DEFAULT 'AED',
    PRIMARY KEY (order_id),
    KEY ix_orders_customer_date (customer_id, order_date),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
) ENGINE=InnoDB;

CREATE TABLE order_item (
    order_item_id INT           NOT NULL AUTO_INCREMENT,
    order_id      INT           NOT NULL,
    product_id    INT           NOT NULL,
    qty           INT           NOT NULL,
    unit_price    DECIMAL(10,2) NOT NULL,
    line_total    DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (order_item_id),
    KEY ix_order_item_order (order_id),
    CONSTRAINT fk_item_order   FOREIGN KEY (order_id)   REFERENCES orders (order_id),
    CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES product (product_id)
) ENGINE=InnoDB;

CREATE TABLE payment (
    payment_id  INT           NOT NULL AUTO_INCREMENT,
    order_id    INT           NOT NULL,
    method      VARCHAR(10)   NOT NULL,                   -- CARD / POINTS / WALLET
    amount      DECIMAL(12,2) NOT NULL,
    status      VARCHAR(10)   NOT NULL,                   -- CAPTURED / REFUNDED
    paid_at     DATETIME      NOT NULL,
    PRIMARY KEY (payment_id),
    KEY ix_payment_order (order_id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE=InnoDB;

CREATE TABLE interaction (
    interaction_id INT          NOT NULL AUTO_INCREMENT,
    customer_id    INT          NOT NULL,
    channel        VARCHAR(10)  NOT NULL,                 -- CALL / EMAIL / CHAT / SOCIAL
    type           VARCHAR(20)  NOT NULL,                 -- COMPLAINT / ENQUIRY / FEEDBACK / BOOKING_CHANGE
    subject        VARCHAR(200) NULL,
    sentiment      SMALLINT     NOT NULL DEFAULT 0,       -- -1 / 0 / 1
    status         VARCHAR(10)  NOT NULL DEFAULT 'OPEN',  -- OPEN / CLOSED
    opened_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at      DATETIME     NULL,
    PRIMARY KEY (interaction_id),
    KEY ix_interaction_customer (customer_id, opened_at),
    CONSTRAINT fk_interaction_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
) ENGINE=InnoDB;

CREATE TABLE loyalty_txn (
    txn_id       INT          NOT NULL AUTO_INCREMENT,
    customer_id  INT          NOT NULL,
    order_id     INT          NULL,
    points       INT          NOT NULL,                   -- negative for redemptions
    reason       VARCHAR(20)  NOT NULL,                   -- PURCHASE / BONUS / REDEMPTION
    txn_date     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (txn_id),
    KEY ix_loyalty_customer (customer_id, txn_date),
    CONSTRAINT fk_loyalty_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
) ENGINE=InnoDB;

-- Output of sp_score_customers (batch churn scoring)
CREATE TABLE customer_score (
    customer_id  INT         NOT NULL,
    churn_score  INT         NOT NULL,
    risk_band    VARCHAR(10) NOT NULL,                    -- LOW / MEDIUM / HIGH
    scored_at    DATETIME    NOT NULL,
    PRIMARY KEY (customer_id),
    CONSTRAINT fk_score_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
) ENGINE=InnoDB;

INSERT INTO loyalty_tier_rule VALUES ('BLUE',     0,     1.00);
INSERT INTO loyalty_tier_rule VALUES ('SILVER',   5000,  1.25);
INSERT INTO loyalty_tier_rule VALUES ('GOLD',     20000, 1.50);
INSERT INTO loyalty_tier_rule VALUES ('PLATINUM', 60000, 2.00);
