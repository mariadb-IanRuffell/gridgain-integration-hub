#!/usr/bin/env bash
# Run SQL against the local Oracle container as the TRAVEL user.
#   ./sqlplus.sh -f sql/01_schema.sql                run a script file
#   ./sqlplus.sh "select count(*) from booking"      run one statement (";" appended)
#   ORA_USER=sys ORA_PASS=oracle ./sqlplus.sh ...    other login (SYS connects AS SYSDBA)
set -euo pipefail
U="${ORA_USER:-travel}"; P="${ORA_PASS:-travel}"
CONN="$U/$P@//localhost/FREEPDB1"; [ "$U" = sys ] && CONN="sys/$P@//localhost/FREEPDB1 as sysdba"
if [ "${1:-}" = "-f" ]; then
  docker exec -i oracle sqlplus -s "$CONN" < "$2"
else
  printf 'set pagesize 200 linesize 220 feedback on\n%s;\n' "$1" | docker exec -i oracle sqlplus -s "$CONN"
fi
