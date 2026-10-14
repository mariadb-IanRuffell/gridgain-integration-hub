#!/usr/bin/env bash
# Generate a steady stream of changes in Sybase so CDC has something to replicate.
#
#   ./gen-load.sh [seconds=30] [rate=50]             touch customers: ~rate changes/s (only updated_at changes)
#   ./gen-load.sh --orders [seconds=30] [rate=5]     place NEW orders: ~rate orders/s, through the stored procedures
#
# --orders runs sp_create_order + 1-3 x sp_add_order_item + sp_complete_order for random active customers and
# products. Each order writes ~8-12 rows (orders, order_item, payment, loyalty_txn, customer points/tier, product
# stock), so 5 orders/s is roughly 50 CDC events/s. If a line fails (e.g. out of stock) the order is CANCELLED, as an
# abandoned order would be. Orders and stock are REAL changes: `./deploy.sh` resets Sybase to the seed data.
# Watch it live at http://localhost:8080/
set -euo pipefail
cd "$(dirname "$0")"

MODE=touch; ARGS=()
for a in "$@"; do
  case "$a" in
    --orders) MODE=orders ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option $a (see --help)" >&2; exit 2 ;;
    *) ARGS+=("$a") ;;
  esac
done
SECS="${ARGS[0]:-30}"
if [ "$MODE" = orders ]; then RATE="${ARGS[1]:-5}"; else RATE="${ARGS[1]:-50}"; fi

# Pacing: rate >= 10/s -> every 100 ms do rate/10 units; slower -> one unit every 1000/rate ms.
if [ "$RATE" -ge 10 ]; then K=$(( RATE / 10 )); TICK_MS=100; else K=1; TICK_MS=$(( 1000 / RATE )); fi
ITER=$(( SECS * 1000 / TICK_MS ))
DELAY=$(printf '00:00:%02d:%03d' $(( TICK_MS / 1000 )) $(( TICK_MS % 1000 )))

if [ "$MODE" = touch ]; then
  echo "generating ~$(( K * 1000 / TICK_MS )) changes/s for ${SECS}s (~$(( K * 1000 / TICK_MS * SECS )) events)"
  ./isql.sh "declare @i int, @lo int
select @i = 0
while @i < $ITER
begin
    select @lo = 1 + (@i * $K) % 240
    update customer set updated_at = getdate() where customer_id >= @lo and customer_id < @lo + $K
    select @i = @i + 1
    waitfor delay '$DELAY'
end" > /dev/null
else
  N=$(( K * 1000 / TICK_MS * SECS ))
  echo "placing ~$(( K * 1000 / TICK_MS )) orders/s for ${SECS}s (~$N orders, ~$(( N * 10 )) CDC events)"
  # Result: last line printed is "placed=<completed> cancelled=<abandoned>"
  ./isql.sh "declare @i int, @j int, @c int, @items int, @cid int, @oid int, @pid int, @qty int, @r int, @rc int
declare @bad int, @placed int, @cancelled int
select @i = 0, @placed = 0, @cancelled = 0
while @i < $ITER
begin
    select @j = 0
    while @j < $K
    begin
        select @r = convert(int, rand() * 251) + 1
        select @cid = null
        select @cid = customer_id from customer where customer_id = @r and status = 'ACTIVE'
        if @cid is not null
        begin
            select @oid = null, @bad = 0, @c = 0
            select @items = 1 + convert(int, rand() * 3)
            exec @rc = sp_create_order @cid, 'APP', @oid output
            if @rc <> 0 select @bad = 1
            while @c < @items and @bad = 0
            begin
                select @pid = 1 + convert(int, rand() * 24)
                select @qty = 1 + convert(int, rand() * 2)
                exec @rc = sp_add_order_item @oid, @pid, @qty
                if @rc <> 0 select @bad = 1
                select @c = @c + 1
            end
            if @bad = 0
            begin
                exec @rc = sp_complete_order @oid, 'CARD'
                select @placed = @placed + 1
            end
            else if @oid is not null
            begin
                update orders set status = 'CANCELLED' where order_id = @oid
                select @cancelled = @cancelled + 1
            end
        end
        select @j = @j + 1
    end
    select @i = @i + 1
    waitfor delay '$DELAY'
end
select 'placed=' + convert(varchar(12), @placed) + ' cancelled=' + convert(varchar(12), @cancelled) as result" \
    | grep -E "placed=" | sed 's/^ *//'
fi
echo "done"
