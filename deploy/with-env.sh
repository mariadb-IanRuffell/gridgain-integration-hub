#!/usr/bin/env bash
# Run any command with cluster.env exported, so the Java clients (cdc, api,
# phase2, loadtest, hub) pick up the physical-server hostnames and ports.
#
#   ./with-env.sh ../cdc/run.sh init
#   ./with-env.sh ../cdc/run.sh run
#   ./with-env.sh ../phase2/run.sh parity all
#   ./with-env.sh ../loadtest/run.sh run oltp-updown
#   ./with-env.sh ../hub/run.sh
#
# Equivalent to:  source deploy/cluster.env  &&  <command>
set -euo pipefail
cd "$(dirname "$0")"
[ -f cluster.env ] || { echo "cluster.env not found — copy cluster.env.example and edit it"; exit 1; }
source ./cluster.env
[ "$#" -ge 1 ] || { echo "usage: with-env.sh <command> [args...]"; exit 2; }
exec "$@"
