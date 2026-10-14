#!/usr/bin/env bash
# Build the job jar and roll it out to the 3-node cluster, one node at a time (no downtime, no data reload:
# the data is persisted). A node loads job classes at start, so each node must be restarted once.
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests package
cp target/gg-compute.jar ../gridgain/deploy/gg-compute.jar

servers() { docker logs "$1" 2>&1 | grep "Topology snapshot" | tail -1 | sed -E 's/.*servers=([0-9]+).*/\1/'; }

for n in gridgain1 gridgain2 gridgain3; do
  watcher=$([ "$n" = gridgain1 ] && echo gridgain2 || echo gridgain1)   # a node that stays up
  echo -n "restarting $n"
  (cd ../gridgain && docker compose restart "$n" >/dev/null)
  sleep 8
  for _ in $(seq 1 60); do [ "$(servers $watcher)" = "3" ] && break; echo -n .; sleep 2; done
  [ "$(servers $watcher)" = "3" ] || { echo " FAILED to rejoin"; exit 1; }
  echo " rejoined"
done
echo "rolled out to all 3 nodes"
