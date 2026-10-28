#!/usr/bin/env bash
# Build the readers and (re)deploy both containers.   ./build.sh   |   docker logs -f gg-cdc  /  gg-cdc-oracle
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests package
docker compose up -d --build --force-recreate   # force-recreate so a rebuilt image is always picked up (compose skips containers whose service def is unchanged)
sleep 8
docker logs --tail 3 gg-cdc
docker logs --tail 3 gg-cdc-oracle
