-- Customer 360 schema for SAP/Sybase ASE 16.0
use testdb
go

if object_id('customer_score') is not null drop table customer_score
go
if object_id('loyalty_txn') is not null drop table loyalty_txn
go
if object_id('interaction') is not null drop table interaction
go
if object_id('payment') is not null drop table payment
go
if object_id('order_item') is not null drop table order_item
go
if object_id('orders') is not null drop table orders
go
if object_id('product') is not null drop table product
go
if object_id('address') is not null drop table address
go
if object_id('customer') is not null drop table customer
go
if object_id('loyalty_tier_rule') is not null drop table loyalty_tier_rule
go

-- Reference data: tier thresholds are on trailing-12-month completed spend
create table loyalty_tier_rule (
    tier               varchar(10)   not null,
    min_spend          numeric(14,2) not null,
    points_multiplier  numeric(4,2)  not null,
    constraint pk_loyalty_tier_rule primary key (tier)
)
go

create table customer (
    customer_id      int           identity,
    first_name       varchar(60)   not null,
    last_name        varchar(60)   not null,
    email            varchar(120)  not null,
    phone            varchar(30)   null,
    date_of_birth    date          null,
    nationality      char(2)       null,
    segment          varchar(20)   default 'STANDARD' not null,   -- STANDARD / PREMIUM / CORPORATE
    loyalty_tier     varchar(10)   default 'BLUE' not null,
    loyalty_points   int           default 0 not null,
    marketing_opt_in bit           default 0 not null,
    status           varchar(10)   default 'ACTIVE' not null,     -- ACTIVE / INACTIVE
    created_at       datetime      default getdate() not null,
    updated_at       datetime      default getdate() not null,
    constraint pk_customer primary key (customer_id),
    constraint uq_customer_email unique (email)
)
go

create table address (
    address_id   int          identity,
    customer_id  int          not null,
    addr_type    varchar(10)  not null,                           -- HOME / BILLING / WORK
    line1        varchar(120) not null,
    city         varchar(60)  not null,
    postcode     varchar(20)  null,
    country      char(2)      not null,
    is_primary   bit          default 0 not null,
    constraint pk_address primary key (address_id),
    constraint fk_address_customer foreign key (customer_id) references customer (customer_id)
)
go
create index ix_address_customer on address (customer_id)
go

create table product (
    product_id   int           identity,
    sku          varchar(20)   not null,
    name         varchar(100)  not null,
    category     varchar(20)   not null,       -- FLIGHT / HOTEL / LOUNGE / UPGRADE / INSURANCE / ANCILLARY
    unit_price   numeric(10,2) not null,
    stock_qty    int           not null,
    active       bit           default 1 not null,
    constraint pk_product primary key (product_id),
    constraint uq_product_sku unique (sku)
)
go

create table orders (
    order_id      int           identity,
    customer_id   int           not null,
    order_date    datetime      default getdate() not null,
    status        varchar(10)   default 'NEW' not null,   -- NEW / COMPLETED / CANCELLED
    channel       varchar(10)   default 'WEB' not null,   -- WEB / APP / CALL / AGENT
    total_amount  numeric(12,2) default 0 not null,
    currency      char(3)       default 'AED' not null,
    constraint pk_orders primary key (order_id),
    constraint fk_orders_customer foreign key (customer_id) references customer (customer_id)
)
go
create index ix_orders_customer_date on orders (customer_id, order_date)
go

create table order_item (
    order_item_id int           identity,
    order_id      int           not null,
    product_id    int           not null,
    qty           int           not null,
    unit_price    numeric(10,2) not null,
    line_total    numeric(12,2) not null,
    constraint pk_order_item primary key (order_item_id),
    constraint fk_item_order   foreign key (order_id)   references orders (order_id),
    constraint fk_item_product foreign key (product_id) references product (product_id)
)
go
create index ix_order_item_order on order_item (order_id)
go

create table payment (
    payment_id  int           identity,
    order_id    int           not null,
    method      varchar(10)   not null,                   -- CARD / POINTS / WALLET
    amount      numeric(12,2) not null,
    status      varchar(10)   not null,                   -- CAPTURED / REFUNDED
    paid_at     datetime      not null,
    constraint pk_payment primary key (payment_id),
    constraint fk_payment_order foreign key (order_id) references orders (order_id)
)
go
create index ix_payment_order on payment (order_id)
go

create table interaction (
    interaction_id int          identity,
    customer_id    int          not null,
    channel        varchar(10)  not null,                 -- CALL / EMAIL / CHAT / SOCIAL
    type           varchar(20)  not null,                 -- COMPLAINT / ENQUIRY / FEEDBACK / BOOKING_CHANGE
    subject        varchar(200) null,
    sentiment      smallint     default 0 not null,       -- -1 / 0 / 1
    status         varchar(10)  default 'OPEN' not null,  -- OPEN / CLOSED
    opened_at      datetime     default getdate() not null,
    closed_at      datetime     null,
    constraint pk_interaction primary key (interaction_id),
    constraint fk_interaction_customer foreign key (customer_id) references customer (customer_id)
)
go
create index ix_interaction_customer on interaction (customer_id, opened_at)
go

create table loyalty_txn (
    txn_id       int          identity,
    customer_id  int          not null,
    order_id     int          null,
    points       int          not null,                   -- negative for redemptions
    reason       varchar(20)  not null,                   -- PURCHASE / BONUS / REDEMPTION
    txn_date     datetime     default getdate() not null,
    constraint pk_loyalty_txn primary key (txn_id),
    constraint fk_loyalty_customer foreign key (customer_id) references customer (customer_id)
)
go
create index ix_loyalty_customer on loyalty_txn (customer_id, txn_date)
go

-- Output of sp_score_customers (batch churn scoring)
create table customer_score (
    customer_id  int         not null,
    churn_score  int         not null,
    risk_band    varchar(10) not null,                    -- LOW / MEDIUM / HIGH
    scored_at    datetime    not null,
    constraint pk_customer_score primary key (customer_id),
    constraint fk_score_customer foreign key (customer_id) references customer (customer_id)
)
go

insert loyalty_tier_rule values ('BLUE',     0,     1.00)
insert loyalty_tier_rule values ('SILVER',   5000,  1.25)
insert loyalty_tier_rule values ('GOLD',     20000, 1.50)
insert loyalty_tier_rule values ('PLATINUM', 60000, 2.00)
go
