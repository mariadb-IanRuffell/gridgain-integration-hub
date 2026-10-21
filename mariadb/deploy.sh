#!/usr/bin/env bash
# (Re)build the Customer 360 schema, seed data, stored procedures and CDC objects in MariaDB.   ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"
docker compose up -d >/dev/null
echo -n "waiting for MariaDB"; until [ "$(docker inspect mariadb --format '{{.State.Health.Status}}')" = healthy ]; do echo -n .; sleep 2; done; echo
python3 generate_seed.py
python3 generate_cdc.py
for f in sql/01_schema.sql sql/02_seed.sql sql/03_procs.sql sql/04_cdc.sql; do
  echo "=== $f"; ./sql.sh -f "$f" 2>&1 | grep -iE "^ERROR|error" || true
done
./sql.sh "select 'customer' t, count(*) n from customer union all select 'orders', count(*) from orders union all select 'order_item', count(*) from order_item union all select 'payment', count(*) from payment union all select 'interaction', count(*) from interaction union all select 'loyalty_txn', count(*) from loyalty_txn"
