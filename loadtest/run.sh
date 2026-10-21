#!/usr/bin/env bash
# OLTP load tester for the GridGain cluster (ramps up and down, with a governor that protects the cluster). See README "Load test".
#   ./run.sh prepare [profile]               create and seed the test table
#   ./run.sh run oltp-updown [key=value ...] run a test, e.g.  peak_threads=16 cycles=2
#   ./run.sh stop                            ramp a running test down
#   ./run.sh cleanup                         drop the test table
# Env: GG_JDBC (default: the three local nodes)
set -euo pipefail
cd "$(dirname "$0")"
[ -d target/lib ] || mvn -q -DskipTests package
exec java @../cdc/jvm.args -cp "target/classes:target/lib/*" loadtest.LoadTest "$@"
