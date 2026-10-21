#!/usr/bin/env bash
# Usage: ./run.sh init | run | once | status | verify
# Env:   MARIADB_URL, MARIADB_USER, MARIADB_PASSWORD, ORA_URL, ORA_USER, ORA_PASSWORD, GG_URL (defaults target the local containers)
set -euo pipefail
cd "$(dirname "$0")"
[ -d target/lib ] || mvn -q -DskipTests package
exec java @jvm.args -cp "target/classes:target/lib/*" cdc.Main "$@"
