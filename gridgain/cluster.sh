#!/usr/bin/env bash
# GridGain 3-node persistent cluster helper.
#   ./cluster.sh up       start all nodes, wait for 3 servers, activate (first start only needs it), show state
#   ./cluster.sh status   cluster state, topology and baseline
#   ./cluster.sh down     stop the nodes, KEEP the data volumes
#   ./cluster.sh wipe     stop the nodes and DELETE the data volumes (fresh, empty cluster)
set -euo pipefail
cd "$(dirname "$0")"
ctl() { docker exec "${NODE:-gridgain1}" /opt/gridgain/bin/control.sh "$@" 2>&1; }

servers() { docker logs "${NODE:-gridgain1}" 2>&1 | grep "Topology snapshot" | tail -1 | sed -E 's/.*servers=([0-9]+).*/\1/'; }

case "${1:-status}" in
  up)
    docker compose up -d
    echo -n "waiting for 3 servers"
    for _ in $(seq 1 60); do [ "$(servers 2>/dev/null || echo 0)" = "3" ] && break; echo -n .; sleep 2; done; echo
    [ "$(servers)" = "3" ] || { echo "cluster did not reach 3 servers"; exit 1; }
    sleep 3
    if ctl --state | grep -qi "inactive"; then ctl --activate | tail -1; sleep 3; fi
    ctl --state | grep -i "cluster is"
    ;;
  status)
    ctl --state | grep -i "cluster is"
    ctl --baseline | grep -vE "JVM_OPTS|^Control|Copyright|^User:|^Time:|^Command|^Arguments|^---|Execution time|completed execution" 
    ;;
  down) docker compose down ;;
  wipe) docker compose down -v ;;
  *) echo "usage: $0 up|status|down|wipe"; exit 2 ;;
esac
