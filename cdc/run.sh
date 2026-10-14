#!/usr/bin/env bash
# Usage: ./run.sh init | run | once | status | verify
# Env:   ASE_URL, ASE_USER, ASE_PASSWORD, GG_URL (defaults target llama.local ASE and local GridGain)
set -euo pipefail
cd "$(dirname "$0")"
[ -d target/lib ] || mvn -q -DskipTests package
exec java @jvm.args -cp "target/classes:target/lib/*" cdc.Main "$@"
