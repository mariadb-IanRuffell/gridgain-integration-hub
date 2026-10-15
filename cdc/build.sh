#!/usr/bin/env bash
# Build the readers and (re)deploy both containers.   ./build.sh   |   docker logs -f gg-cdc  /  gg-cdc-oracle
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests package
docker compose up -d --build
sleep 8
docker logs --tail 3 gg-cdc
docker logs --tail 3 gg-cdc-oracle
