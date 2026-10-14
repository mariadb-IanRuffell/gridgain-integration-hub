#!/usr/bin/env bash
# Build the reader and (re)deploy it as the gg-cdc container.   ./build.sh   |   docker logs -f gg-cdc
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests package
docker compose up -d --build
sleep 6
docker logs --tail 5 gg-cdc
