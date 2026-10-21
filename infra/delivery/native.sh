#!/bin/sh
# Builds the ledger as a GraalVM native executable, in a GraalVM container, and measures it against
# the JVM image. Stage 14; measured, not shipped.
#
#   set -a; . infra/.env; set +a         # Compose services running
#   infra/delivery/native.sh <jvm-image> # output: target/evidence/14-native.txt
#   SKIP_BUILD=1 infra/delivery/native.sh <jvm-image>   # measure an executable built earlier
#
# The build needs a 10 GB heap and runs for more than 15 minutes; on this 24 GB host, next to its
# usual load, it did not finish (docs/evidence/14-native.txt). Run it where 14 GB are free.
#
# The build runs in ghcr.io/graalvm/native-image-community:25 (Spring Boot 4 needs GraalVM 25) on
# this repository and the local Maven repository, so the machine needs no GraalVM. The enforcer's
# JDK rule is skipped for that build only: it pins the JVM build to 21, and this one is not a JVM
# build. The executable is then started three times in the JVM image's base (same OS libraries)
# against the Compose services, as infra/delivery/startup.sh starts the JVM image.
set -eu
jvm_image=${1:?usage: native.sh <jvm-image>}
cd "$(dirname "$0")/../.."
# Refuse to start a build the host cannot hold next to what it already runs.
available_mb=$(awk '/MemAvailable/ {print int($2 / 1024)}' /proc/meminfo)
if [ "${SKIP_BUILD:-}" != 1 ] && [ "$available_mb" -lt 14000 ]; then
  echo "only $available_mb MB available; the build needs about 12 GB plus headroom" >&2
  exit 1
fi
out=target/evidence/14-native.txt
graal=ghcr.io/graalvm/native-image-community:25
base=$(sed -n 's/^ARG BASE=//p' bootstrap/Dockerfile)
mkdir -p "$(dirname "$out")" target/native

started=$(date +%s)
set +e
if [ "${SKIP_BUILD:-}" = 1 ]; then
  echo "(build skipped: SKIP_BUILD=1, measuring the executable already in bootstrap/target)" > target/native/skip.log
  rc=0
else
# As this user, not the image's root: the build writes into this checkout's target/ directories.
# Hard limits: memory with no swap, and three CPUs. Without them the build took this host down
# (docs/evidence/14-native.txt): it swapped until every other process stalled, and the OOM killer
# never ran. With them, a build that does not fit is killed alone. 12 GB holds the measured
# 8.55 GB heap peak and the JVM around it; a host with less free memory than that should not run
# this at all.
docker run --rm --memory="${NATIVE_MEMORY:-12g}" --memory-swap="${NATIVE_MEMORY:-12g}" --cpus=3 \
  --user "$(id -u):$(id -g)" -e HOME=/tmp -e MAVEN_USER_HOME=/m2 \
  -v "$PWD:/src" -v "$HOME/.m2:/m2" -w /src --entrypoint ./mvnw "$graal" \
  -B -ntp -Pnative -pl bootstrap -am package -DskipTests -Dmaven.repo.local=/m2/repository \
  -Dspotless.check.skip=true -Denforcer.skip=true -Djacoco.skip=true > target/native/build.log 2>&1
rc=$?
fi
set -e
build_s=$(( $(date +%s) - started ))

{
  echo "GraalVM native image of the ledger, built in $graal on $(nproc) CPUs ($(uname -m))."
  echo "build: exit $rc after $build_s s (log: target/native/build.log)"
  grep -E "GraalVM Native Image|Peak RSS|Finished generating|Produced artifacts|Error|error:" target/native/build.log \
    | grep -v "^\[INFO\] Download" | head -20 | sed 's/^/  /'
  if [ "$rc" -ne 0 ] || [ ! -x bootstrap/target/sl-ledger ]; then
    echo "no executable; see the build log"
    exit 0
  fi
  echo "executable: $(du -m bootstrap/target/sl-ledger | cut -f1) MB"
  echo
  echo "started s, ready ms, RSS after readiness (three runs; local profile, Compose services):"
  i=0
  while [ $i -lt 3 ]; do
    docker rm -f sl-ledger-native >/dev/null 2>&1 || true
    t0=$(date +%s%3N)
    docker run -d --name sl-ledger-native --network host -v "$PWD/bootstrap/target/sl-ledger:/app/sl-ledger:ro" \
      -e SPRING_PROFILES_ACTIVE=local -e SERVER_PORT=8097 \
      -e LEDGER_DB_URL="jdbc:postgresql://127.0.0.1:5432/${POSTGRES_DB:?source infra/.env}" \
      -e LEDGER_DB_USER="$POSTGRES_USER" -e LEDGER_DB_PASSWORD="$POSTGRES_PASSWORD" \
      -e LEDGER_REDIS_PASSWORD="$REDIS_PASSWORD" --entrypoint /app/sl-ledger "$base" >/dev/null
    ok=1
    until curl -fsS -o /dev/null localhost:8097/actuator/health/readiness 2>/dev/null; do
      if [ "$(docker inspect -f '{{.State.Running}}' sl-ledger-native)" != true ]; then ok=0; break; fi
      sleep 0.1
    done
    t1=$(date +%s%3N)
    if [ $ok = 0 ]; then
      echo "  run $i: the executable exited; its last lines:"
      docker logs sl-ledger-native 2>&1 | tail -15 | sed 's/^/    /'
      break
    fi
    rss=$(docker exec sl-ledger-native cat /sys/fs/cgroup/memory.current 2>/dev/null | awk '{printf "%.0f MB", $1/1048576}')
    s=$(docker logs sl-ledger-native 2>&1 | grep -m1 'Started SlLedgerApplication' | sed -E 's/.* in ([0-9.]+) seconds.*/\1/')
    echo "  $s s, $((t1 - t0)) ms, $rss"
    docker stop -t 30 sl-ledger-native >/dev/null
    i=$((i + 1))
  done
  docker rm -f sl-ledger-native >/dev/null 2>&1 || true
  echo
  echo "the JVM image ($jvm_image) under the same conditions, container memory after readiness:"
  docker rm -f sl-ledger-jvmcmp >/dev/null 2>&1 || true
  infra/delivery/run-local.sh "$jvm_image" sl-ledger-jvmcmp 8098 >/dev/null
  until curl -fsS -o /dev/null localhost:8098/actuator/health/readiness 2>/dev/null; do sleep 0.2; done
  echo "  $(docker exec sl-ledger-jvmcmp cat /sys/fs/cgroup/memory.current | awk '{printf "%.0f MB", $1/1048576}')"
  docker rm -f sl-ledger-jvmcmp >/dev/null
} | tee "$out"
