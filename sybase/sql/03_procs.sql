-- Stored procedures for Customer 360 (migration candidates for GridGain compute jobs)
use testdb
go

-----------------------------------------------------------------------------
-- 1. sp_recalc_loyalty_tier: derive tier from trailing-12-month completed spend.
--    Pattern: read-aggregate + reference-data lookup + conditional update.
-----------------------------------------------------------------------------
if object_id('sp_recalc_loyalty_tier') is not null drop procedure sp_recalc_loyalty_tier
go
create procedure sp_recalc_loyalty_tier
    @customer_id int,
    @new_tier    varchar(10) = null output
as
begin
    declare @spend numeric(14,2), @old_tier varchar(10)

    select @old_tier = loyalty_tier from customer where customer_id = @customer_id
    if @old_tier is null
    begin
        raiserror 20002 'Customer not found'
        return -1
    end

    select @spend = isnull(sum(total_amount), 0)
      from orders
     where customer_id = @customer_id
       and status = 'COMPLETED'
       and order_date >= dateadd(dd, -365, getdate())

    select @new_tier = tier
      from loyalty_tier_rule
     where min_spend = (select max(min_spend) from loyalty_tier_rule where min_spend <= @spend)

    if @new_tier <> @old_tier
        update customer set loyalty_tier = @new_tier, updated_at = getdate()
         where customer_id = @customer_id

    return 0
end
go

-----------------------------------------------------------------------------
-- 2. sp_customer_360: single-call read of the whole customer view.
--    Pattern: multiple result sets from many tables (the classic "360" read).
-----------------------------------------------------------------------------
if object_id('sp_customer_360') is not null drop procedure sp_customer_360
go
create procedure sp_customer_360
    @customer_id int
as
begin
    declare @spend_12m numeric(14,2), @next_threshold numeric(14,2)

    if not exists (select 1 from customer where customer_id = @customer_id)
    begin
        raiserror 20002 'Customer not found'
        return -1
    end

    -- result set 1: profile
    select c.customer_id, c.first_name, c.last_name, c.email, c.phone, c.nationality,
           c.segment, c.loyalty_tier, c.loyalty_points, c.marketing_opt_in, c.status,
           c.created_at, a.city, a.country
      from customer c
      left join address a on a.customer_id = c.customer_id and a.is_primary = 1
     where c.customer_id = @customer_id

    -- result set 2: order summary
    select count(*)                       as order_count,
           isnull(sum(total_amount), 0)   as lifetime_spend,
           isnull(avg(total_amount), 0)   as avg_order_value,
           max(order_date)                as last_order_date,
           isnull(sum(case when order_date >= dateadd(dd, -365, getdate())
                           then total_amount else 0 end), 0) as spend_12m
      from orders
     where customer_id = @customer_id and status = 'COMPLETED'

    -- result set 3: five most recent orders
    select top 5 order_id, order_date, status, channel, total_amount, currency
      from orders
     where customer_id = @customer_id
     order by order_date desc

    -- result set 4: top three categories by spend
    select top 3 p.category, sum(oi.line_total) as spend, sum(oi.qty) as qty
      from orders o
      join order_item oi on oi.order_id = o.order_id
      join product p     on p.product_id = oi.product_id
     where o.customer_id = @customer_id and o.status = 'COMPLETED'
     group by p.category
     order by sum(oi.line_total) desc, p.category   -- tie-break: keeps results deterministic

    -- result set 5: service interactions
    select count(*)                                          as total_interactions,
           isnull(sum(case when status = 'OPEN' then 1 else 0 end), 0) as open_cases,
           max(opened_at)                                    as last_contact,
           isnull(avg(convert(float, sentiment)), 0)         as avg_sentiment
      from interaction
     where customer_id = @customer_id

    -- result set 6: loyalty position and distance to next tier
    select @spend_12m = isnull(sum(total_amount), 0)
      from orders
     where customer_id = @customer_id and status = 'COMPLETED'
       and order_date >= dateadd(dd, -365, getdate())
    select @next_threshold = min(min_spend) from loyalty_tier_rule where min_spend > @spend_12m

    select c.loyalty_tier, c.loyalty_points, @spend_12m as spend_12m,
           (select tier from loyalty_tier_rule where min_spend = @next_threshold) as next_tier,
           @next_threshold - @spend_12m as spend_to_next_tier
      from customer c
     where c.customer_id = @customer_id

    return 0
end
go

-----------------------------------------------------------------------------
-- 3-5. Order flow: create -> add items (stock check) -> complete (payment, points, tier).
--      Pattern: multi-statement transactions, optimistic stock decrement,
--      procedure-to-procedure call, error signalling via raiserror/return codes.
-----------------------------------------------------------------------------
if object_id('sp_create_order') is not null drop procedure sp_create_order
go
create procedure sp_create_order
    @customer_id int,
    @channel     varchar(10) = 'WEB',
    @order_id    int output
as
begin
    if not exists (select 1 from customer where customer_id = @customer_id and status = 'ACTIVE')
    begin
        raiserror 20002 'Customer not found or inactive'
        return -1
    end

    insert orders (customer_id, order_date, status, channel, total_amount, currency)
    values (@customer_id, getdate(), 'NEW', @channel, 0, 'AED')

    select @order_id = @@identity
    return 0
end
go

if object_id('sp_add_order_item') is not null drop procedure sp_add_order_item
go
create procedure sp_add_order_item
    @order_id   int,
    @product_id int,
    @qty        int
as
begin
    declare @price numeric(10,2)

    if @qty <= 0
    begin
        raiserror 20004 'Quantity must be positive'
        return -4
    end

    if not exists (select 1 from orders where order_id = @order_id and status = 'NEW')
    begin
        raiserror 20005 'Order not found or not open'
        return -5
    end

    select @price = unit_price from product where product_id = @product_id and active = 1
    if @price is null
    begin
        raiserror 20006 'Product not found or inactive'
        return -6
    end

    begin tran add_item
        update product set stock_qty = stock_qty - @qty
         where product_id = @product_id and stock_qty >= @qty

        if @@rowcount = 0
        begin
            rollback tran add_item
            raiserror 20003 'Insufficient stock'
            return -3
        end

        insert order_item (order_id, product_id, qty, unit_price, line_total)
        values (@order_id, @product_id, @qty, @price, @price * @qty)

        update orders set total_amount = total_amount + (@price * @qty)
         where order_id = @order_id
    commit tran add_item

    return 0
end
go

if object_id('sp_complete_order') is not null drop procedure sp_complete_order
go
create procedure sp_complete_order
    @order_id       int,
    @payment_method varchar(10) = 'CARD'
as
begin
    declare @customer_id int, @total numeric(12,2), @tier varchar(10),
            @mult numeric(4,2), @points int

    select @customer_id = customer_id, @total = total_amount
      from orders where order_id = @order_id and status = 'NEW'

    if @customer_id is null
    begin
        raiserror 20005 'Order not found or not open'
        return -5
    end
    if @total <= 0
    begin
        raiserror 20007 'Order has no items'
        return -7
    end

    select @tier = loyalty_tier from customer where customer_id = @customer_id
    select @mult = points_multiplier from loyalty_tier_rule where tier = @tier
    select @points = convert(int, @total * @mult)

    begin tran complete_order
        update orders set status = 'COMPLETED' where order_id = @order_id

        insert payment (order_id, method, amount, status, paid_at)
        values (@order_id, @payment_method, @total, 'CAPTURED', getdate())

        insert loyalty_txn (customer_id, order_id, points, reason, txn_date)
        values (@customer_id, @order_id, @points, 'PURCHASE', getdate())

        update customer set loyalty_points = loyalty_points + @points, updated_at = getdate()
         where customer_id = @customer_id
    commit tran complete_order

    exec sp_recalc_loyalty_tier @customer_id
    return 0
end
go

-----------------------------------------------------------------------------
-- 6. sp_score_customers: batch churn scoring with a cursor and upsert.
--    Pattern: row-by-row batch over all customers (ideal for distributed compute,
--    colocated by customer_id).
--    Score 0-100: recency (0-60) + 12m frequency (0-30) + open cases (0-10) + negative sentiment (10)
-----------------------------------------------------------------------------
if object_id('sp_score_customers') is not null drop procedure sp_score_customers
go
create procedure sp_score_customers
    @as_of       datetime = null,
    @customer_id int = null
as
begin
    declare @cid int, @last_order datetime, @orders_12m int, @open_cases int,
            @avg_sent float, @recency int, @score int, @band varchar(10)

    if @as_of is null select @as_of = getdate()

    declare cust_cur cursor for
        select customer_id from customer
         where status = 'ACTIVE' and (@customer_id is null or customer_id = @customer_id)

    open cust_cur
    fetch cust_cur into @cid

    while (@@sqlstatus = 0)
    begin
        select @last_order = max(order_date),
               @orders_12m = sum(case when order_date >= dateadd(dd, -365, @as_of) then 1 else 0 end)
          from orders
         where customer_id = @cid and status = 'COMPLETED' and order_date <= @as_of
        select @orders_12m = isnull(@orders_12m, 0)

        select @open_cases = count(*) from interaction
         where customer_id = @cid and status = 'OPEN'

        select @avg_sent = isnull(avg(convert(float, sentiment)), 0)
          from interaction
         where customer_id = @cid and opened_at >= dateadd(dd, -365, @as_of)

        select @recency = case when @last_order is null then 9999
                               else datediff(dd, @last_order, @as_of) end

        select @score =
              (case when @recency <= 90  then 0
                    when @recency <= 180 then 20
                    when @recency <= 365 then 40 else 60 end)
            + (case when @orders_12m >= 4 then 0
                    when @orders_12m >= 2 then 10
                    when @orders_12m = 1  then 20 else 30 end)
            + (case when @open_cases * 5 > 10 then 10 else @open_cases * 5 end)
            + (case when @avg_sent < 0 then 10 else 0 end)
        if @score > 100 select @score = 100

        select @band = case when @score < 30 then 'LOW'
                            when @score < 60 then 'MEDIUM' else 'HIGH' end

        update customer_score
           set churn_score = @score, risk_band = @band, scored_at = @as_of
         where customer_id = @cid
        if @@rowcount = 0
            insert customer_score (customer_id, churn_score, risk_band, scored_at)
            values (@cid, @score, @band, @as_of)

        fetch cust_cur into @cid
    end

    close cust_cur
    deallocate cursor cust_cur

    -- summary result set
    select risk_band, count(*) as customers, avg(churn_score) as avg_score
      from customer_score
     where scored_at = @as_of
     group by risk_band
     order by risk_band

    return 0
end
go
