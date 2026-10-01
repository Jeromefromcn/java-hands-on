#!/bin/sh
# Checks and tests the alert rules with promtool, in Docker. Not part of ./mvnw verify: it needs a
# container runtime and an image pull, and the gate must not depend on either.
set -eu
cd "$(dirname "$0")"
IMAGE="${PROMETHEUS_IMAGE:-prom/prometheus:v3.5.0}"
run() { docker run --rm -v "$PWD:/rules:ro" -w /rules --entrypoint promtool "$IMAGE" "$@"; }
run check rules alerts.yml
run test rules alerts_test.yml
