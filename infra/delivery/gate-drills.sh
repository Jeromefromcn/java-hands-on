#!/bin/bash
# Makes every gate of .github/workflows/ci.yml fail once, on purpose. Stage 14.
#
#   infra/delivery/gate-drills.sh push [gate...]   # one throwaway branch per gate, pushed
#   infra/delivery/gate-drills.sh report    # after the runs end: which job failed at which step,
#                                           # which jobs never ran; target/evidence/14-gate-drills.txt
#   infra/delivery/gate-drills.sh clean     # delete the branches, local and remote
#
# Each branch is main plus the smallest change that should trip exactly one gate. Branches are
# gate-drill/<gate>, except the image scan's, which is drill/image-scan: the image job runs only on
# main and drill/*. A broken change never reaches main: the branches are deleted after the report.
set -euo pipefail
cd "$(dirname "$0")/../.."
base=$(git rev-parse origin/main)

declare -A BRANCH=(
  [format]=gate-drill/format
  [compile]=gate-drill/compile
  [enforcer]=gate-drill/enforcer
  [unit-test]=gate-drill/unit-test
  [archunit]=gate-drill/archunit
  [coverage]=gate-drill/coverage
  [dependency-scan]=gate-drill/dependency-scan
  [image-scan]=drill/image-scan
)
ORDER=(format compile enforcer unit-test archunit coverage dependency-scan image-scan)

FLAT=domain/src/main/java/com/jerome/slledger/domain/accrual/FlatRate.java
break_gate() { # gate -> edits the work tree, prints what it did
  case "$1" in
    format)
      sed -i 's/^public record FlatRate(Rate rate) implements AccrualRule {$/&\n  private static final int   DRILL = 1;/' "$FLAT"
      echo "FlatRate: a field declared with three spaces (Spotless, validate phase)" ;;
    compile)
      sed -i 's/^public record FlatRate(Rate rate) implements AccrualRule {$/&\n  static final Integer DRILL = new Integer(1);/' "$FLAT"
      echo "FlatRate: new Integer(int), deprecated for removal (-Xlint:all -Werror)" ;;
    enforcer)
      sed -i 's#^  <modules>#  <repositories>\n    <repository>\n      <id>drill</id>\n      <url>https://repo.example.invalid/maven2</url>\n    </repository>\n  </repositories>\n\n  <modules>#' pom.xml
      echo "a <repositories> block in the parent POM (requireNoRepositories, ci profile only)" ;;
    unit-test)
      sed -i 's/BigDecimal DAY_COUNT_BASIS = new BigDecimal("365");/BigDecimal DAY_COUNT_BASIS = new BigDecimal("366");/' \
        domain/src/main/java/com/jerome/slledger/domain/accrual/AccrualRule.java
      echo "the day-count basis set to 366 (AccrualRuleTest)" ;;
    archunit)
      sed -i '0,/  @Access.ReadPositions/{/  @Access.ReadPositions/d}' \
        adapter-web/src/main/java/com/jerome/slledger/adapter/web/loan/LoanReadController.java
      echo "LoanReadController's first handler without its @PreAuthorize annotation (ArchitectureIT)" ;;
    coverage)
      { echo 'package com.jerome.slledger.domain.accrual;'
        echo ''
        echo '/** Gate drill: code no test reaches. */'
        echo 'final class Untested {'
        echo ''
        echo '  private Untested() {}'
        for i in $(seq 1 60); do
          echo ''
          echo "  static int f$i(int x) {"
          echo "    int y = x + $i;"
          echo "    if (y > $i) {"
          echo '      y = y * 2;'
          echo '    }'
          echo '    return y - x;'
          echo '  }'
        done
        echo '}'; } > domain/src/main/java/com/jerome/slledger/domain/accrual/Untested.java
      echo "60 untested, well-formatted methods in domain (domain's 94% line floor)" ;;
    dependency-scan)
      sed -i 's#<lz4-java.version>[^<]*</lz4-java.version>#<lz4-java.version>1.10.1</lz4-java.version>#' pom.xml
      echo "lz4-java back to 1.10.1, CVE-2026-106451 (infra/security/scan.sh)" ;;
    image-scan)
      sed -i 's#^ARG BASE=.*#ARG BASE=eclipse-temurin:21.0.2_13-jre-jammy#' bootstrap/Dockerfile
      echo "the image's base back to Temurin 21.0.2 on Ubuntu 22.04 of early 2024 (Trivy on the image)" ;;
  esac
}

case "${1:-}" in
  push)
    work=$(mktemp -d)
    git worktree add -q --detach "$work/tree" "$base"
    trap 'git worktree remove --force "$work/tree"; rm -rf "$work"' EXIT
    shift
    gates=("$@")
    [ ${#gates[@]} -eq 0 ] && gates=("${ORDER[@]}")
    for gate in "${gates[@]}"; do
      branch=${BRANCH[$gate]}
      (cd "$work/tree" && git checkout -q -B "$branch" "$base" \
        && what=$(break_gate "$gate") \
        && git add -A && git commit -q -m "gate drill: $gate - $what" \
        && { git push -q -f origin "$branch" || { sleep 5; git push -q -f origin "$branch"; }; } \
        && echo "$gate -> $branch: $what")
    done ;;
  report)
    out=target/evidence/14-gate-drills.txt
    mkdir -p "$(dirname "$out")"
    {
      echo "Gate drills against origin/main $(git rev-parse --short "$base"): each branch breaks one gate."
      echo
      for gate in "${ORDER[@]}"; do
        branch=${BRANCH[$gate]}
        run=$(gh run list --workflow ci.yml --branch "$branch" --limit 1 --json databaseId,conclusion,url \
          --jq '.[0] | "\(.databaseId) \(.conclusion) \(.url)"')
        id=${run%% *}
        echo "== $gate ($branch): run ${run#* }"
        gh run view "$id" --json jobs --jq '.jobs[] | "  job \(.name): \(.conclusion // .status)"
          + (if .conclusion == "failure" then " at step \"" + ([.steps[] | select(.conclusion == "failure") | .name] | first) + "\""
             + (if ([.steps[] | select(.conclusion == "skipped" and .name != "Test reports")] | length) > 0
                then "; not run: " + ([.steps[] | select(.conclusion == "skipped" and .name != "Test reports") | .name] | join(", ")) else "" end)
             else "" end)'
        # The line that names the cause, per kind of gate; never the lines that passed.
        { gh run view "$id" --log-failed 2>/dev/null || true; } \
          | grep -E "format violations|Rule [0-9]+: .* failed|requireNoRepositories|warnings found and -Werror|<<< FAILURE! -- in|ArchitectureIT|Coverage checks have not been met|lines covered ratio is|Total: [1-9]|CVE-[0-9]+-[0-9]+ .* (HIGH|CRITICAL)" \
          | grep -vE "passed|Tests run: [0-9]+, Failures: 0, Errors: 0" \
          | sed -E 's/^[^\t]*\t[^\t]*\t[0-9TZ:.-]+ //' | awk '!seen[$0]++' | head -4 | cut -c1-200 | sed 's/^/    /' || true
        echo
      done
    } | tee "$out" ;;
  clean)
    for gate in "${ORDER[@]}"; do
      branch=${BRANCH[$gate]}
      git push -q origin --delete "$branch" 2>/dev/null || true
      git branch -q -D "$branch" 2>/dev/null || true
    done ;;
  *) echo "usage: gate-drills.sh push|report|clean" >&2; exit 2 ;;
esac
