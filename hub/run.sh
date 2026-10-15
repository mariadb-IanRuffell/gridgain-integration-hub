#!/usr/bin/env bash
# Run every cross-source query in queries.sql against the GridGain hub and print the results.   ./run.sh [file.sql]
set -euo pipefail
cd "$(dirname "$0")"
LIB="../phase2/target/lib"
[ -d "$LIB" ] || (cd ../phase2 && mvn -q -DskipTests package)
exec java @../cdc/jvm.args -cp "$LIB/*" Query.java "${1:-queries.sql}"
