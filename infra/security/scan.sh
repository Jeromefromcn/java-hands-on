#!/bin/sh
# Scans the dependency set for known vulnerabilities with Trivy, in Docker. Not part of ./mvnw
# verify: it needs a container runtime and a vulnerability database download, and the gate must not
# depend on either; stage 14 puts it in the pipeline. Exit status 1 when anything HIGH or CRITICAL
# has a fix that is not yet taken and is not suppressed, with a reason, in /.trivyignore.
#
# Two passes. The poms, which Trivy resolves against the local Maven repository (a dependency whose
# version it cannot determine is skipped with a warning, so this pass can miss things); and the
# libraries the application actually runs on, copied out of the resolved runtime classpath of
# `bootstrap` and scanned as jars, which is what ships and cannot be skipped. The second is the one
# that counts; the first is cheap and names the pom to change.
#
# --ignore-unfixed: a vulnerability with no fixed release cannot be acted on by upgrading, and
# failing on it would make the scan red with no way to turn it green; those are read in the table
# (run without --ignore-unfixed to see them) and judged, not gated.
set -eu
cd "$(dirname "$0")/../.."
IMAGE="${TRIVY_IMAGE:-aquasec/trivy:0.65.0}"
CACHE="${TRIVY_CACHE_DIR:-$HOME/.cache/trivy}"
M2="${M2_REPO:-$HOME/.m2/repository}"
mkdir -p "$CACHE"

trivy() {
  docker run --rm \
    -v "$PWD:/repo:ro" -v "$CACHE:/root/.cache/trivy" -v "$M2:/root/.m2/repository:ro" \
    -w /repo "$IMAGE" "$@" --skip-version-check
}

FLAGS="--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1"

echo "== poms"
trivy fs $FLAGS --skip-dirs target --skip-dirs .git --skip-dirs .idea --skip-dirs labs .

echo "== runtime classpath of bootstrap"
./mvnw -q -pl bootstrap dependency:copy-dependencies -DincludeScope=runtime \
  -DoutputDirectory=target/scan-libs
trivy rootfs $FLAGS bootstrap/target/scan-libs
