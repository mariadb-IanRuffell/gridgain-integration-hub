use testdb
go
print '--- procs present'
select name from sysobjects where type = 'P' and name like 'sp[_]%' order by name
go
print '--- seeded tier vs recomputed tier (expect 0 mismatches)'
select count(*) as mismatches from customer c
 where c.loyalty_tier <> (select r.tier from loyalty_tier_rule r where r.min_spend =
        (select max(min_spend) from loyalty_tier_rule
          where min_spend <= (select isnull(sum(o.total_amount),0) from orders o
                               where o.customer_id = c.customer_id and o.status = 'COMPLETED'
                                 and o.order_date >= dateadd(dd,-365,getdate()))))
go
print '--- tier distribution'
select loyalty_tier, count(*) as customers from customer group by loyalty_tier order by 2 desc
go
print '--- sp_customer_360 (customer 1: six result sets)'
exec sp_customer_360 1
go
print '--- order flow on an active SILVER customer: create, add 2 items, bad qty, over-stock, complete'
declare @oid int, @rc int, @cid int
select @cid = min(customer_id) from customer where status = 'ACTIVE' and loyalty_tier = 'SILVER'
select customer_id, loyalty_tier, loyalty_points from customer where customer_id = @cid
exec @rc = sp_create_order @cid, 'APP', @oid output
select @rc as create_rc, @oid as order_id
exec @rc = sp_add_order_item @oid, 4, 2
select @rc as add_item_rc
exec @rc = sp_add_order_item @oid, 17, 1
select @rc as add_item_rc
exec @rc = sp_add_order_item @oid, 4, 0
select @rc as bad_qty_rc
exec @rc = sp_add_order_item @oid, 4, 999999
select @rc as no_stock_rc
exec @rc = sp_complete_order @oid, 'CARD'
select @rc as complete_rc
exec @rc = sp_complete_order @oid, 'CARD'
select @rc as complete_again_rc
select o.order_id, o.status, o.total_amount, (select count(*) from order_item where order_id = o.order_id) as items,
       (select sum(amount) from payment where order_id = o.order_id) as paid,
       (select sum(points) from loyalty_txn where order_id = o.order_id) as points
  from orders o where o.order_id = @oid
select customer_id, loyalty_tier, loyalty_points from customer where customer_id = @cid
select product_id, stock_qty from product where product_id in (4, 17)
go
print '--- sp_score_customers'
exec sp_score_customers
go
select top 5 c.customer_id, c.last_name, s.churn_score, s.risk_band from customer_score s join customer c on c.customer_id = s.customer_id order by s.churn_score desc
go
