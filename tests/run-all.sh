#!/usr/bin/env bash
# Run every unit test in the project. None of them needs the cluster, Sybase or Oracle.   tests/run-all.sh [python|cdc|phase2|api]
set -euo pipefail
cd "$(dirname "$0")/.."
want="${1:-all}"
run() { [ "$want" = all ] || [ "$want" = "$1" ]; }
if run python; then echo "== python generators and hub queries"; python3 -W error -m unittest discover -s tests/python; fi
for m in cdc phase2 api; do
  if run "$m"; then echo "== $m"; (cd "$m" && mvn -q test); echo "$m: ok"; fi
done
