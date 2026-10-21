#!/bin/sh
# Startup time of the ledger's image: baseline, CDS, Spring AOT, AOT with CDS. Stage 14.
#
#   set -a; . infra/.env; set +a       # Compose services running
#   infra/delivery/startup.sh [runs]   # default 5 runs per variant; output target/evidence/14-startup.txt
#
# Builds the four images from the working tree (baseline: bootstrap/Dockerfile --target jvm, which
# is what ships; cds: --target cds; the AOT pair from ./mvnw -Paot package), then for
# each variant starts a container N times against the Compose services (local profile, host
# network) and records Spring's own "Started ... in" figure and the wall time from `docker run` to
# the first 200 from the readiness probe, polled every 100 ms. Each container is stopped before the
# next starts; the machine should be otherwise idle. Prints medians, minimum and maximum.
set -eu
cd "$(dirname "$0")/../.."
runs=${1:-5}
out=target/evidence/14-startup.txt
work=$(mktemp -d)
trap 'rm -rf "$work"; docker rm -f sl-ledger-startup >/dev/null 2>&1 || true' EXIT

build_jar_image() { # tag, maven args, docker target (jvm: no CDS; cds: what ships)
  ./mvnw -q -B package -DskipTests -Dspotless.check.skip=true $2 >/dev/null
  rm -rf "$work/ctx" && mkdir "$work/ctx" && cp bootstrap/target/bootstrap-*.jar "$work/ctx/"
  docker build -q -f bootstrap/Dockerfile --target "$3" -t "$1" "$work/ctx" >/dev/null
}
overlay() { # tag, dockerfile, from
  docker build -q -f "infra/delivery/startup/$2" --build-arg "IMAGE=$3" -t "$1" "$work/ctx" >/dev/null
}

if [ "${SKIP_BUILD:-}" != 1 ]; then
echo "building images" >&2
build_jar_image sl-ledger:startup-aot-base -Paot jvm
overlay sl-ledger:startup-aot Dockerfile.aot sl-ledger:startup-aot-base
# The AOT image cannot train without a database (see Dockerfile.cds-trained): train it by running.
mkdir -p "$work/train" && chmod 777 "$work/train"
aot_opts=$(docker image inspect sl-ledger:startup-aot --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | sed -n 's/^JAVA_TOOL_OPTIONS=//p')
infra/delivery/run-local.sh sl-ledger:startup-aot sl-ledger-train 8093 -v "$work/train:/train" \
  -e JAVA_TOOL_OPTIONS="$aot_opts -XX:ArchiveClassesAtExit=/train/application.jsa -Dspring.context.exit=onRefresh -Xlog:cds=off -Xlog:cds+dynamic=off" >/dev/null
docker wait sl-ledger-train >/dev/null && docker rm sl-ledger-train >/dev/null
docker build -q -f infra/delivery/startup/Dockerfile.cds-trained --build-arg IMAGE=sl-ledger:startup-aot \
  -t sl-ledger:startup-aot-cds "$work/train" >/dev/null
build_jar_image sl-ledger:startup-baseline "" jvm
build_jar_image sl-ledger:startup-cds "" cds
fi

now_ms() { date +%s%3N; }
measure() { # image -> lines "started_s process_s ready_ms"
  i=0
  while [ $i -lt "$runs" ]; do
    t0=$(now_ms)
    infra/delivery/run-local.sh "$1" sl-ledger-startup 8092 >/dev/null
    until curl -fsS -o /dev/null localhost:8092/actuator/health/readiness 2>/dev/null; do sleep 0.1; done
    t1=$(now_ms)
    line=$(docker logs sl-ledger-startup 2>&1 | grep -m1 "Started SlLedgerApplication")
    started=$(echo "$line" | sed -E 's/.* in ([0-9.]+) seconds.*/\1/')
    process=$(echo "$line" | sed -E 's/.*running for ([0-9.]+)\).*/\1/')
    warn=$(docker logs sl-ledger-startup 2>&1 | grep -c -i "\[cds\]" || true)
    echo "$started $process $((t1 - t0)) $warn"
    docker stop -t 30 sl-ledger-startup >/dev/null
    i=$((i + 1))
  done
}
stats() { # column -> "median min max"
  sort -n | awk '{ v[NR] = $1 } END { m = (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2;
    printf "%s %s %s", m, v[1], v[NR] }'
}

mkdir -p "$(dirname "$out")"
{
  echo "Startup of the ledger image, local profile against infra/compose.yaml, host network."
  echo "$(nproc) CPUs ($(uname -m)), Docker $(docker version --format '{{.Server.Version}}'), $runs runs per variant; no limit on the container."
  echo "started = Spring's \"Started ... in\"; process = its \"process running for\" (JVM start to ready context);"
  echo "ready = wall time from docker run to the first 200 from /actuator/health/readiness (100 ms polling)."
  echo
  printf "%-10s %-24s %-24s %-24s %s\n" variant "started s (med min max)" "process s (med min max)" "ready ms (med min max)" "image"
  for v in baseline cds aot aot-cds; do
    image=sl-ledger:startup-$v
    measure "$image" > "$work/$v"
    size=$(docker image inspect "$image" --format '{{.Size}}' | awk '{printf "%.0f MB", $1/1000000}')
    printf "%-10s %-24s %-24s %-24s %s\n" "$v" "$(cut -d' ' -f1 "$work/$v" | stats)" \
      "$(cut -d' ' -f2 "$work/$v" | stats)" "$(cut -d' ' -f3 "$work/$v" | stats)" "$size"
  done
  echo
  echo "What AOT fixes at build time: each image started with LEDGER_MESSAGING_ENABLED=false (the local"
  echo "profile it was built under sets true). Kafka client lines in the log after readiness:"
  for v in baseline aot; do
    infra/delivery/run-local.sh "sl-ledger:startup-$v" sl-ledger-startup 8092 \
      -e LEDGER_MESSAGING_ENABLED=false >/dev/null
    until curl -fsS -o /dev/null localhost:8092/actuator/health/readiness 2>/dev/null; do sleep 0.5; done
    sleep 3
    printf "  %-9s %s\n" "$v" "$(docker logs sl-ledger-startup 2>&1 | grep -c 'Kafka version')"
    docker stop -t 30 sl-ledger-startup >/dev/null
  done
  echo
  echo "raw (started process ready_ms cds-warnings):"
  for v in baseline cds aot aot-cds; do echo "$v:"; sed 's/^/  /' "$work/$v"; done
} | tee "$out"
