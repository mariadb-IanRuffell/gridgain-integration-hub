# Running on physical servers

The rest of this repo is wired for a single Mac running everything as Docker
containers on one bridge network, where hosts resolve each other by service name
(`gridgain1`, `mariadb`, `oracle`). This directory adds what you need to run the
same stack across **real servers**, driven by **one config file**:
[`cluster.env`](cluster.env.example).

Every Java client already reads its endpoints from environment variables, and the
GridGain node config now takes its discovery list from `-DGG_HOST1..3`. So you set
your hostnames and ports once, in `cluster.env`, and everything else follows.

## 0. Topology

A typical five-host layout (collapse onto fewer hosts if you like — it's all
driven by `cluster.env`):

| Role | Count | Notes |
|---|---|---|
| GridGain node | 3 | `GG_HOST1/2/3`; native persistence on local disk |
| MariaDB (CRM source) | 1 | `MARIADB_HOST` |
| Oracle (travel source) | 1 | `ORA_HOST` |
| REST API | 1 | `API_HOST`; a thin client, can sit anywhere that can reach the nodes |

CDC readers, the API, loadtest and hub are thin clients — run them on any host
that can reach the three GridGain nodes (commonly the API host, or an ops host).

## 1. Prerequisites

- **Each GridGain host:** Docker. (Alternatively the GridGain CE distribution zip
  — see [Bare metal](#bare-metal-without-docker) at the end.)
- **MariaDB host:** MariaDB 11.4, UTC time zone, and `log_bin_trust_function_creators=1`
  (or the `crm` user has `SUPER`) so the triggers/procedures load. Database `crm`
  and user `crm` created.
- **Oracle host:** Oracle 23ai (service `FREEPDB1`), user `travel` created.
- **The host you build/deploy from:** JDK 17+, Maven, `python3`, and — for
  `load-sources.sh` — the `mariadb` client and Oracle `sqlplus`.
- **Firewall** open between hosts (see [the port table](#ports)).

## 2. Fill in the config

```bash
cd deploy
cp cluster.env.example cluster.env
$EDITOR cluster.env          # set the GG_HOST*, DB hosts, ports, credentials
```

`cluster.env` is git-ignored (it holds credentials). It is the **only** file you
edit for hostnames/ports — `gridgain-node.sh`, `load-sources.sh`, `with-env.sh`,
and the GridGain discovery list all read from it.

## 3. Build everything

From the repo root, on the build host:

```bash
for m in cdc api loadtest phase2; do (cd "$m" && mvn -q -DskipTests package); done
```

## 4. Start the GridGain cluster

Copy the repo (or at least `gridgain/` and `deploy/`, with your `cluster.env`) to
each GridGain host, then on each one run **its** node index:

```bash
# on GG_HOST1
deploy/gridgain-node.sh 1
# on GG_HOST2
deploy/gridgain-node.sh 2
# on GG_HOST3
deploy/gridgain-node.sh 3
```

Each starts the `gridgain/community` image with **host networking**, mounts
`gridgain/config/node.xml` and `gridgain/deploy/` (the compute-job jar), persists
to `$GG_WORK_DIR/nodeN`, and passes `-DGG_HOST1..3` so discovery uses your real
hostnames.

A persistent cluster starts **inactive**. Once all three have joined, activate
once (on any GridGain host):

```bash
docker exec gg-node1 /opt/gridgain/bin/control.sh --activate
docker exec gg-node1 /opt/gridgain/bin/control.sh --state      # "Cluster is active"
```

## 5. Deploy the Phase-2 compute jobs

The jobs run **inside** the nodes, so the jar must be on each node's classpath
(`gridgain/deploy/`) before that node starts. The build produced it at
`phase2/target/gg-compute.jar`:

```bash
# copy to each GridGain host's gridgain/deploy/ BEFORE step 4, or copy now and
# restart the nodes one at a time (data is persisted, so no reload):
scp phase2/target/gg-compute.jar  user@GG_HOST1:.../gridgain/deploy/gg-compute.jar   # repeat for 2, 3
# then, one at a time, keeping a quorum up:
ssh user@GG_HOST1 'docker restart gg-node1'   # wait for it to rejoin, then node2, then node3
```

(`phase2/redeploy.sh` automates the rolling restart, but only for the single-host
Docker-Compose layout; across servers, do the `scp` + rolling `docker restart`
above.)

## 6. Load the two source databases

From the build host (needs the `mariadb` and `sqlplus` clients):

```bash
deploy/load-sources.sh            # both; or: load-sources.sh mariadb | oracle
```

This runs the deterministic generators and applies `01_schema` → `02_seed` →
`03_procs` → `04_cdc` to the hosts in `cluster.env`.

## 7. Snapshot into GridGain and start CDC

```bash
cd deploy
./with-env.sh ../cdc/run.sh init                      # MariaDB: create GG tables, snapshot, set watermark
CDC_SOURCE=oracle ./with-env.sh ../cdc/run.sh init    # Oracle: same

# then stream continuously (one process per source — run under systemd/nohup):
./with-env.sh ../cdc/run.sh run                       # MariaDB reader
CDC_SOURCE=oracle ./with-env.sh ../cdc/run.sh run     # Oracle reader
```

`./with-env.sh` just sources `cluster.env` and execs the command, so the reader
picks up `MARIADB_URL` / `ORA_URL` / `GG_URL` from your config. Verify any time:

```bash
./with-env.sh ../cdc/run.sh status
./with-env.sh ../cdc/run.sh verify                    # exit 1 on any row difference
```

## 8. Start the REST API

On `API_HOST` (a JDK-17 host that can reach the nodes):

```bash
source deploy/cluster.env
java -jar api/target/*.jar                            # reads GG_JDBC_URL / GG_ADDRESSES
# API on http://API_HOST:8080  — dashboard at /  , Swagger at /swagger-ui.html
```

Smoke test, parity and load (all honour `cluster.env` via `with-env.sh`):

```bash
cd deploy
GG_JDBC="$GG_JDBC" ../api/smoke-test.sh               # or point the script at API_HOST:8080
./with-env.sh ../phase2/run.sh parity all
./with-env.sh ../loadtest/run.sh prepare && ./with-env.sh ../loadtest/run.sh run oltp-updown
```

## Ports

Open these between hosts:

| From | To | Port | Purpose |
|---|---|---|---|
| each GridGain node | the other two | `47500–47509` TCP | discovery |
| each GridGain node | the other two | `47100–47109` TCP | communication (SPI) |
| clients (CDC, API, loadtest, hub) | every GridGain node | `10800` TCP | thin client / JDBC |
| CDC reader host | MariaDB host | `3306` TCP | CRM source |
| CDC reader host | Oracle host | `1521` TCP | travel source |
| API consumers | API host | `8080` TCP | REST |

The communication port `47100` is GridGain's default and needs no config; just
open it. If a host has multiple NICs, set a specific bind address in
`gridgain/config/node.xml` (`localHost`).

## Notes

- **Affinity awareness** is currently disabled in the API thin client
  (`api/.../GridGainConfig.java`) because, under Docker, nodes advertised
  unreachable container addresses. On physical servers with host networking the
  nodes advertise reachable addresses, so you *may* flip it on for better routing
  — that's a one-line code change, optional.
- **Security:** this PoC uses plaintext credentials and no TLS. Before anything
  beyond a lab, enable SSL on the GridGain client connector and discovery, use
  real secrets management instead of `cluster.env`, and lock the ports down.
- **Nothing here changes the single-Mac workflow.** `node.xml` still defaults to
  the `gridgain1/2/3` service names when `-DGG_HOST*` is not set, so the existing
  `gridgain/cluster.sh up` and per-module `./run.sh` keep working unchanged.

## Bare metal (without Docker)

If you'd rather not run Docker on the GridGain hosts: download the GridGain
Community Edition 8.10 distribution, copy `gridgain/config/node.xml` and
`phase2/target/gg-compute.jar` (into `libs/` or `libs/external/`), and start each
node with the same system properties `gridgain-node.sh` passes:

```bash
IGNITE_HOME=/opt/gridgain \
JVM_OPTS="-DNODE_NAME=node1 -DGG_HOST1=$GG_HOST1 -DGG_HOST2=$GG_HOST2 -DGG_HOST3=$GG_HOST3" \
  bin/ignite.sh config/node.xml
```

Point native persistence at a data dir via `IGNITE_WORK_DIR`, then activate as in
step 4 with `bin/control.sh --activate`.
