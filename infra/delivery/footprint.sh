#!/bin/sh
# The ledger's memory in a container: what the kernel counts against the limit and what the JVM
# says it holds, heap and the rest. Stage 14.
#
#   set -a; . infra/.env; set +a
#   infra/delivery/footprint.sh <image> <memory-limit> [extra JAVA_TOOL_OPTIONS]
#
# Starts the image with --memory, waits for readiness, sends 500 authenticated reads, then reads
# the container's memory use (cgroup, the number an OOM kill is decided on) and the JVM's own meters
# from /actuator/prometheus with a client-credentials token, as stage 13's Prometheus does.
set -eu
image=${1:?usage: footprint.sh <image> <limit> [java opts]}
limit=${2:?limit}
extra=${3:-}
name=sl-ledger-footprint
cd "$(dirname "$0")/../.."
infra/delivery/run-local.sh "$image" "$name" 8091 --memory="$limit" --memory-swap="$limit" \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError $extra" >/dev/null
trap 'docker rm -f "$name" >/dev/null' EXIT
until curl -fsS localhost:8091/actuator/health/readiness >/dev/null 2>&1; do sleep 1; done
token=$(infra/delivery/kc-token.sh ops1)
i=0; while [ $i -lt 500 ]; do
  curl -fsS -o /dev/null -H "Authorization: Bearer $token" "localhost:8091/loans?size=20"; i=$((i+1))
done
metrics_token=$(curl -fsS http://127.0.0.1:8180/realms/ledger/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=prometheus \
  --data-urlencode "client_secret=${PROMETHEUS_CLIENT_SECRET:?source infra/.env}" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
m=$(curl -fsS -H "Authorization: Bearer $metrics_token" localhost:8091/actuator/prometheus)
mb() { awk '{ s += $NF } END { printf "%.0f MB", s / 1048576 }'; }
echo "limit $limit, JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError $extra"
echo "container (cgroup memory.current): $(docker exec "$name" cat /sys/fs/cgroup/memory.current | awk '{printf "%.0f MB", $1/1048576}')"
echo "heap max:        $(echo "$m" | grep '^jvm_memory_max_bytes{area="heap"' | awk '$NF > 0' | mb)"
echo "heap committed:  $(echo "$m" | grep '^jvm_memory_committed_bytes{area="heap"' | mb)"
echo "heap used:       $(echo "$m" | grep '^jvm_memory_used_bytes{area="heap"' | mb)"
echo "non-heap used:   $(echo "$m" | grep '^jvm_memory_used_bytes{area="nonheap"' | mb) (metaspace, code cache, compressed class space)"
echo "  of which metaspace $(echo "$m" | grep '^jvm_memory_used_bytes{area="nonheap",id="Metaspace"' | mb)"
echo "direct buffers:  $(echo "$m" | grep '^jvm_buffer_memory_used_bytes{id="direct"' | mb)"
echo "live threads:    $(echo "$m" | grep '^jvm_threads_live_threads' | awk '{print $NF}')"
echo "collector:       $(echo "$m" | grep -o 'jvm_gc_pause_seconds_count{[^}]*gc="[^"]*"' | sed 's/.*gc="//; s/"$//' | sort -u | tr '\n' ' ')"
