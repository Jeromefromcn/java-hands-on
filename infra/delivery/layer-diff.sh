#!/bin/sh
# Which image layers a one-line change rebuilds. Stage 14.
#
#   infra/delivery/layer-diff.sh [commit]     # default: HEAD; output: target/evidence/14-layer-diff.txt
#
# In a temporary worktree of the commit, every build with --no-cache as on a fresh CI runner: builds
# the image twice unchanged (a layer that differs there differs on every build), then changes one
# line of business code in domain (an exception message: a comment would not change a byte of the
# jar), builds again, and prints the images' layer digests side by side. Then the same pair with Boot's default layers
# (the <layers> configuration removed from bootstrap/pom.xml, and the Dockerfile's ledger COPY with it), where the project's modules sit in
# snapshot-dependencies. Leaves no worktree and no image behind.
set -eu
cd "$(dirname "$0")/../.."
commit=$(git rev-parse --short=12 "${1:-HEAD}")
out="$PWD/target/evidence/14-layer-diff.txt"
work=$(mktemp -d)
git worktree add -q --detach "$work/tree" "$commit"
trap 'git worktree remove --force "$work/tree"; rm -rf "$work"' EXIT

edit_one_line() {
  sed -i 's/A tiered rate needs at least one tier/A tiered rate needs at least one tier band/' \
    "$1/domain/src/main/java/com/jerome/slledger/domain/accrual/MarginalTieredRate.java"
}
layers() {
  docker image inspect "$1" --format '{{range .RootFS.Layers}}{{println .}}{{end}}' | sed '/^$/d'
}
sizes() {
  # Newest first in docker history; reversed to base-first like RootFS.Layers. Empty-layer
  # instructions (ENV, USER...) have no RootFS entry and are dropped.
  docker history --no-trunc --format '{{.Size}}|{{.CreatedBy}}' "$1" | tac \
    | awk -F'|' '$1 != "0B"' | sed -E 's/\|.*(COPY [^ ]+ [^ ]+|RUN .{0,40}|ADD .{0,30}|WORKDIR .*).*/ \1/; s/\|.*/ (base)/'
}
build() {
  (cd "$work/tree" && ./mvnw -q -B package -DskipTests -Dspotless.check.skip=true \
    && rm -rf bootstrap/target/image-context && mkdir bootstrap/target/image-context \
    && cp bootstrap/target/bootstrap-*.jar bootstrap/target/image-context/ \
    && docker build -q --no-cache -f bootstrap/Dockerfile --build-arg REVISION="$commit" -t "$1" \
         bootstrap/target/image-context >/dev/null)
}
compare() {
  label=$1 a=$2 b=$3
  layers "$a" > "$work/a"; layers "$b" > "$work/b"; sizes "$a" > "$work/s"
  echo "== $label"
  echo "layer  changed  size  instruction"
  paste -d'|' "$work/a" "$work/b" "$work/s" | awk -F'|' '{
    split($3, s, " "); instr = substr($3, length(s[1]) + 2)
    printf "%-5d  %-7s  %-6s %s\n", NR, ($1 == $2 ? "same" : "CHANGED"), s[1], instr }'
  echo "changed layers: $(paste -d' ' "$work/a" "$work/b" | awk '$1 != $2' | wc -l) of $(wc -l < "$work/a")"
  echo
}

mkdir -p "$(dirname "$out")"
{
  echo "Commit $commit; one-line edit: MarginalTieredRate's exception message, in domain."
  echo "Docker $(docker version --format '{{.Server.Version}}'), $(uname -m)."
  echo
  build ld-ledger-a; build ld-ledger-a2
  compare "same commit, built twice" ld-ledger-a ld-ledger-a2
  edit_one_line "$work/tree"; build ld-ledger-b
  compare "one-line edit in domain, ledger layer (layers.xml)" ld-ledger-a ld-ledger-b
  git -C "$work/tree" checkout -q -- .
  sed -i '/<layers>/,/<\/layers>/d' "$work/tree/bootstrap/pom.xml"
  sed -i '/extracted\/ledger/d' "$work/tree/bootstrap/Dockerfile"
  build ld-default-a; edit_one_line "$work/tree"; build ld-default-b
  compare "one-line edit in domain, Boot's default layers (no layers.xml)" ld-default-a ld-default-b
} > "$out"
docker rmi -f ld-ledger-a ld-ledger-a2 ld-ledger-b ld-default-a ld-default-b >/dev/null
cat "$out"
