#!/usr/bin/env bash
# (Re)build the Customer 360 schema, seed data and stored procedures in Sybase ASE.
set -euo pipefail
cd "$(dirname "$0")"
python3 generate_seed.py
python3 generate_cdc.py
for f in sql/01_schema.sql sql/02_seed.sql sql/03_procs.sql sql/04_cdc.sql; do
  echo "=== $f"; ./isql.sh -f "$f" | grep -E "^Msg|Incorrect|error|Error" || true
done
./isql.sh "select 'customer' t, count(*) n from customer union all select 'orders', count(*) from orders union all select 'order_item', count(*) from order_item union all select 'payment', count(*) from payment union all select 'interaction', count(*) from interaction union all select 'loyalty_txn', count(*) from loyalty_txn"
