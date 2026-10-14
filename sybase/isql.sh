#!/usr/bin/env bash
# Run SQL against the ASE on llama via a throwaway client container.
#   ./isql.sh -f sql/01_schema.sql              run a file
#   ./isql.sh "select count(*) from customer"   run an ad-hoc statement ("go" is appended)
set -euo pipefail
HOST="${SYBASE_HOST:-llama.local}"
ASE_IP="${ASE_IP:-192.168.68.73}"; ASE_PORT="${ASE_PORT:-5000}"
USER_="${SYBASE_USER:-tester}"; PASS="${SYBASE_PASSWORD:-guest1234}"; DB="${SYBASE_DB:-testdb}"

if [ "${1:-}" = "-f" ]; then SQL="$(cat "$2")"; else SQL="$1"$'\n'"go"; fi

# Ship the SQL and a small wrapper script to the host, then run both in a client container.
D="/tmp/_isql.$$"
{
  echo "mkdir -p $D"
  echo "cat > $D/wrap.sh <<'WRAPEOF'"
  cat <<WRAP
source /opt/sybase/SYBASE.sh
printf 'ASE\n\tquery tcp ether $ASE_IP $ASE_PORT\n' > \$SYBASE/interfaces
isql -U$USER_ -P$PASS -SASE -D$DB -w250 -i /w/q.sql
WRAP
  echo "WRAPEOF"
  echo "cat > $D/q.sql <<'SQLEOF'"
  printf '%s\n' "$SQL"
  echo "SQLEOF"
  echo "docker run --rm --network host -v $D:/w:ro --entrypoint bash datagrip/sybase /w/wrap.sh"
  echo "rm -rf $D"
} | ssh "$HOST" bash -s
