#!/bin/sh
# Lab 14: what the JVM sees in a container, and the two ways a container runs out of memory.
#
#   labs/14-delivery/heap/run.sh        # output: labs/14-delivery/target/evidence/
#
# Runs in the ledger's own base image (bootstrap/Dockerfile's BASE), so the JVM is the one that
# ships. Allocate.java is compiled here with the JDK .sdkmanrc pins and mounted read-only.
set -eu
cd "$(dirname "$0")"
base=$(sed -n 's/^ARG BASE=//p' ../../../bootstrap/Dockerfile)
out=../target/evidence
mkdir -p "$out" ../target/classes
javac --release 21 -d ../target/classes Allocate.java

jvm() { # docker run args..., then -- , then java args
  docker run --rm "$@"
}

{
  echo "Image: $base"
  echo "Host: $(nproc) CPUs, $(free -m | awk '/Mem:/ {print $2}') MB; Docker $(docker version --format '{{.Server.Version}}')"
  for limit in none 512m 1g; do
    if [ $limit = none ]; then args=""; else args="--memory=$limit"; fi
    for opts in "" "-XX:MaxRAMPercentage=75"; do
      echo
      echo "== --memory=$limit  JAVA_TOOL_OPTIONS='${opts}'"
      # shellcheck disable=SC2086
      jvm $args -e JAVA_TOOL_OPTIONS="$opts" "$base" sh -c \
        'java -XshowSettings:system -version 2>&1 | grep -E "Memory Limit|Effective CPU|Provider" ;
         java -XX:+PrintFlagsFinal -version 2>/dev/null \
           | grep -E " (MaxHeapSize|MaxRAMPercentage|UseContainerSupport|ActiveProcessorCount) |Use(Serial|G1|Parallel|Z)GC .*true" \
           | awk "{print \$2, \$3, \$4}"' 2>&1 | grep -v "Picked up"
    done
  done
  echo
  echo "== --memory=1g --cpus=1.5"
  jvm --memory=1g --cpus=1.5 -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" "$base" sh -c \
    'java -XshowSettings:system -version 2>&1 | grep -E "Memory Limit|Effective CPU" ;
     java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E " (MaxHeapSize|ActiveProcessorCount) |Use(Serial|G1)GC .*true" | awk "{print \$2, \$3, \$4}"' 2>&1 | grep -v "Picked up"
} > "$out/14-container-awareness.txt"
cat "$out/14-container-awareness.txt"

run_until_dead() { # name, memory, java opts
  docker rm -f "$1" >/dev/null 2>&1 || true
  docker run --name "$1" --memory="$2" --memory-swap="$2" \
    -v "$PWD/../target/classes:/lab:ro" -e JAVA_TOOL_OPTIONS="$3" "$base" \
    java -cp /lab Allocate > "$out/$1.log" 2>&1 || true
  echo "== $1: --memory=$2 JAVA_TOOL_OPTIONS='$3'"
  echo "last lines of output:"
  grep -v "^\s*at " "$out/$1.log" | tail -4 | sed 's/^/  /'
  docker inspect "$1" --format '  exit code {{.State.ExitCode}}, OOMKilled={{.State.OOMKilled}}'
  docker rm "$1" >/dev/null
  echo
}
{
  run_until_dead fixed-xmx-above-limit 512m "-Xmx1g"
  run_until_dead percentage-of-limit 512m "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
  run_until_dead percentage-without-exit 512m "-XX:MaxRAMPercentage=75"
} > "$out/14-oomkilled-vs-oome.txt"
cat "$out/14-oomkilled-vs-oome.txt"
