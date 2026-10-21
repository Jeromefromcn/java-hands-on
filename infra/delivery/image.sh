#!/bin/sh
# Builds the ledger's image from the repackaged jar. Stage 14.
#
#   infra/delivery/image.sh [tag]      # default tag: sl-ledger:<short commit>[-dirty]
#
# Packages without tests (the gate is ./mvnw verify, not this script), copies the one jar into an
# otherwise empty build context so nothing else in target/ (or the repository) is sent to the
# daemon, and builds bootstrap/Dockerfile with the commit as the revision. Prints the image id.
set -eu
cd "$(dirname "$0")/../.."
revision=$(git rev-parse --short=12 HEAD)
git diff --quiet HEAD -- . || revision="$revision-dirty"
tag="${1:-sl-ledger:$revision}"

./mvnw -q -B package -DskipTests -Dspotless.check.skip=true
context=bootstrap/target/image-context
rm -rf "$context" && mkdir -p "$context"
cp bootstrap/target/bootstrap-*.jar "$context/"
docker build -q -f bootstrap/Dockerfile --build-arg "REVISION=$revision" -t "$tag" "$context"
