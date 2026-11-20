#!/usr/bin/env bash
# Load the MariaDB (CRM) and Oracle (travel) schema, seed, procedures and CDC
# objects onto the hosts named in cluster.env — the physical-server equivalent of
# mariadb/deploy.sh + oracle/deploy.sh, but over the network instead of docker exec.
#
#   ./load-sources.sh            # both sources
#   ./load-sources.sh mariadb    # just MariaDB
#   ./load-sources.sh oracle     # just Oracle
#
# Requires native clients on THIS host:  mariadb  (MariaDB/MySQL client) and
# sqlplus  (Oracle Instant Client), plus python3 for the deterministic generators.
#
# Server prerequisites (set by the DBA, once):
#   MariaDB : run in UTC (--default-time-zone=+00:00) and allow trigger/function
#             creation (log_bin_trust_function_creators=1, or grant SUPER); the
#             database and the crm user from cluster.env must already exist.
#   Oracle  : the service (FREEPDB1) and the travel user must already exist.
set -euo pipefail
cd "$(dirname "$0")"
[ -f cluster.env ] || { echo "cluster.env not found — copy cluster.env.example and edit it"; exit 1; }
source ./cluster.env
what="${1:-both}"

load_mariadb() {
  echo "### MariaDB  ${MARIADB_HOST}:${MARIADB_PORT}/${MARIADB_DB}"
  ( cd ../mariadb && python3 generate_seed.py && python3 generate_cdc.py )
  for f in 01_schema 02_seed 03_procs 04_cdc; do
    echo "=== mariadb sql/${f}.sql"
    mariadb -h "$MARIADB_HOST" -P "$MARIADB_PORT" -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" \
            "$MARIADB_DB" < "../mariadb/sql/${f}.sql"
  done
}

load_oracle() {
  echo "### Oracle  ${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE}  (user ${ORA_USER})"
  ( cd ../oracle && python3 generate_seed.py && python3 generate_cdc.py )
  # the CDC reader takes a consistent snapshot via DBMS_FLASHBACK (one-time grant)
  echo "grant execute on dbms_flashback to ${ORA_USER};" | \
    sqlplus -s "sys/${ORA_SYS_PASSWORD}@//${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE} as sysdba"
  for f in 01_schema 02_seed 03_procs 04_cdc; do
    echo "=== oracle sql/${f}.sql"
    sqlplus -s "${ORA_USER}/${ORA_PASSWORD}@//${ORA_HOST}:${ORA_PORT}/${ORA_SERVICE}" < "../oracle/sql/${f}.sql"
  done
}

case "$what" in
  both)    load_mariadb; load_oracle ;;
  mariadb) load_mariadb ;;
  oracle)  load_oracle ;;
  *) echo "usage: $0 [both|mariadb|oracle]"; exit 2 ;;
esac
echo "done."
