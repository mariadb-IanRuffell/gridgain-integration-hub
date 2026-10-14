#!/usr/bin/env bash
# Usage: ./run.sh 360 <id> | recalc <id> | score | order ... | parity <...>   (see Main.java)
# Env:   GG_ADDR (default 127.0.0.1:10800), ASE_URL/ASE_USER/ASE_PASSWORD for parity
set -euo pipefail
cd "$(dirname "$0")"
[ -d target/lib ] || mvn -q -DskipTests package
exec java @jvm.args -cp "target/classes:target/lib/*" poc.compute.client.Main "$@"
