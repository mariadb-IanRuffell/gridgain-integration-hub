#!/usr/bin/env bash
# (Re)build the Oracle travel schema, seed data, PL/SQL procedures and CDC objects.   ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"
docker compose up -d >/dev/null
echo -n "waiting for Oracle"; until [ "$(docker inspect oracle --format '{{.State.Health.Status}}')" = healthy ]; do echo -n .; sleep 3; done; echo
python3 generate_seed.py
python3 generate_cdc.py
# the CDC reader needs a consistent snapshot SCN
ORA_USER=sys ORA_PASS=oracle ./sqlplus.sh "grant execute on dbms_flashback to travel" >/dev/null
for f in sql/01_schema.sql sql/02_seed.sql sql/03_procs.sql sql/04_cdc.sql; do
  echo "=== $f"; ./sqlplus.sh -f "$f" 2>&1 | grep -E "ORA-|PLS-|Warning: .*compilation errors" || true
done
./sqlplus.sh "select 'airport' t, count(*) n from airport union all select 'flight', count(*) from flight union all select 'flight_inventory', count(*) from flight_inventory union all select 'booking', count(*) from booking union all select 'booking_segment', count(*) from booking_segment union all select 'flight_event', count(*) from flight_event union all select 'compensation', count(*) from compensation"
