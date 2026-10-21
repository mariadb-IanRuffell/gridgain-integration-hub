#!/usr/bin/env bash
# Generate a steady stream of changes in MariaDB so CDC has something to replicate.
#
#   ./gen-load.sh [seconds=30] [rate=50]             touch customers: ~rate changes/s (only updated_at changes)
#   ./gen-load.sh --orders [seconds=30] [rate=5]     place NEW orders: ~rate orders/s, through the stored procedures
#
# --orders runs sp_create_order + 1-3 x sp_add_order_item + sp_complete_order for random active customers and
# products. Each order writes ~8-12 rows (orders, order_item, payment, loyalty_txn, customer points/tier, product
# stock), so 5 orders/s is roughly 50 CDC events/s. If a line fails (e.g. out of stock) the order is CANCELLED, as an
# abandoned order would be. Orders and stock are REAL changes: `./deploy.sh` resets MariaDB to the seed data.
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
DELAY=$(awk "BEGIN { printf \"%.3f\", $TICK_MS / 1000 }")

if [ "$MODE" = touch ]; then
  echo "generating ~$(( K * 1000 / TICK_MS )) changes/s for ${SECS}s (~$(( K * 1000 / TICK_MS * SECS )) events)"
  ./sql.sh -b "BEGIN NOT ATOMIC
  DECLARE i INT DEFAULT 0;
  DECLARE lo INT;
  WHILE i < $ITER DO
    SET lo = 1 + (i * $K) % 240;
    UPDATE customer SET updated_at = NOW() WHERE customer_id >= lo AND customer_id < lo + $K;
    SET i = i + 1;
    DO SLEEP($DELAY);
  END WHILE;
END" > /dev/null
else
  N=$(( K * 1000 / TICK_MS * SECS ))
  echo "placing ~$(( K * 1000 / TICK_MS )) orders/s for ${SECS}s (~$N orders, ~$(( N * 10 )) CDC events)"
  # Result: last line printed is "placed=<completed> cancelled=<abandoned>"
  ./sql.sh -b "BEGIN NOT ATOMIC
  DECLARE i, j, c, items, cid, oid, pid, qty, r, bad, placed, cancelled INT DEFAULT 0;
  DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET bad = 1;
  WHILE i < $ITER DO
    SET j = 0;
    WHILE j < $K DO
      SET r = FLOOR(RAND() * 251) + 1, cid = NULL;
      SELECT customer_id INTO cid FROM customer WHERE customer_id = r AND status = 'ACTIVE';
      IF cid IS NOT NULL THEN
        SET oid = NULL, bad = 0, c = 0, items = 1 + FLOOR(RAND() * 3);
        CALL sp_create_order(cid, 'APP', oid);
        WHILE c < items AND bad = 0 DO
          SET pid = 1 + FLOOR(RAND() * 24), qty = 1 + FLOOR(RAND() * 2);
          CALL sp_add_order_item(oid, pid, qty);
          SET c = c + 1;
        END WHILE;
        IF bad = 0 THEN
          CALL sp_complete_order(oid, 'CARD');
          IF bad = 0 THEN SET placed = placed + 1; END IF;
        END IF;
        IF bad = 1 AND oid IS NOT NULL THEN
          UPDATE orders SET status = 'CANCELLED' WHERE order_id = oid AND status = 'NEW';
          SET cancelled = cancelled + 1;
        END IF;
      END IF;
      SET j = j + 1;
    END WHILE;
    SET i = i + 1;
    DO SLEEP($DELAY);
  END WHILE;
  SELECT CONCAT('placed=', placed, ' cancelled=', cancelled) AS result;
END" | grep -E "placed=" | sed -E 's/.*(placed=[0-9]+ cancelled=[0-9]+).*/\1/' || true
fi
echo "done"
