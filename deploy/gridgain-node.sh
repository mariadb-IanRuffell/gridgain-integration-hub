#!/usr/bin/env bash
# Start ONE GridGain server node on THIS physical host, using Docker with host
# networking (so the node binds the host's real IP for discovery :47500 and the
# thin-client port :10800, reachable from the other servers and the clients).
#
#   ./gridgain-node.sh 1        # on the host you set as GG_HOST1 in cluster.env
#   ./gridgain-node.sh 2        # on GG_HOST2
#   ./gridgain-node.sh 3        # on GG_HOST3
#
# After all three are up, activate the persistent cluster ONCE (any node):
#   docker exec gg-node1 /opt/gridgain/bin/control.sh --activate
#
# One node per host (host networking means all three would collide on one host).
set -euo pipefail
cd "$(dirname "$0")"
[ -f cluster.env ] || { echo "cluster.env not found — copy cluster.env.example and edit it"; exit 1; }
source ./cluster.env

idx="${1:?usage: gridgain-node.sh <1|2|3>}"
case "$idx" in 1|2|3) ;; *) echo "node index must be 1, 2 or 3"; exit 2 ;; esac
name="node${idx}"
work="${GG_WORK_DIR:-/var/lib/gridgain}/${name}"
mkdir -p "$work"

config_dir="$(cd ../gridgain/config && pwd)"
deploy_dir="$(cd ../gridgain/deploy && pwd)"   # holds gg-compute.jar (the Phase-2 jobs)

docker rm -f "gg-${name}" >/dev/null 2>&1 || true
exec docker run -d --name "gg-${name}" --restart unless-stopped \
  --network host \
  -v "${config_dir}:/config:ro" \
  -v "${deploy_dir}:/opt/gridgain/libs/external:ro" \
  -v "${work}:/opt/gridgain/work" \
  -e CONFIG_URI=/config/node.xml \
  -e JVM_OPTS="-Xms${GG_XMS:-512m} -Xmx${GG_XMX:-1536m} -XX:+UseG1GC -DIGNITE_QUIET=false -DNODE_NAME=${name} -DGG_HOST1=${GG_HOST1} -DGG_HOST2=${GG_HOST2} -DGG_HOST3=${GG_HOST3}" \
  "${GG_IMAGE}"
