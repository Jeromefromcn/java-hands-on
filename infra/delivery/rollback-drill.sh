#!/bin/bash
# The rollback drill of stage 14: promote a release by digest in the GitOps repository, detect the
# failure with infra/delivery/smoke.sh (which must be running), revert, and log the times.
#
#   infra/delivery/rollback-drill.sh <gitops-checkout> <bad-digest> <good-digest>   # log: target/evidence/rollback-drill.log
#
# The run recorded in docs/evidence/14-rollback-drill.txt used an earlier copy of this script whose
# revert line was `git revert --no-edit HEAD -q`: git rejects -q there, the script logged a revert
# it had not made, and 509 s passed before that was noticed. This copy reverts without the flag and
# stops if the revert or the push fails.
set -euo pipefail
gitops=${1:?usage: rollback-drill.sh <gitops-checkout> <bad-digest> <good-digest>}
bad=${2:?bad digest}
good=${3:?good digest}
image=ghcr.io/jeromefromcn/sl-ledger
manifests="k3s/apps/sl-ledger/k8s/ledger.yaml k3s/apps/sl-ledger/k8s/accrual-cronjob.yaml"
out="$(cd "$(dirname "$0")/../.." && pwd)/target/evidence/rollback-drill.log"
mkdir -p "$(dirname "$out")"; : > "$out"
log() { echo "$(date -u +%H:%M:%S) $*" | tee -a "$out"; }
refresh() { kubectl -n argocd annotate application sl-ledger argocd.argoproj.io/refresh=hard --overwrite >/dev/null; }
deployed() { kubectl -n sl-ledger get deploy sl-ledger -o jsonpath='{.spec.template.spec.containers[0].image}'; }
settled() { [ "$(kubectl -n sl-ledger get deploy sl-ledger -o jsonpath='{.status.updatedReplicas} {.status.readyReplicas} {.status.replicas}')" = "2 2 2" ]; }
smoke_lines() { kubectl -n sl-ledger logs smoke | wc -l; }

cd "$gitops"
sed -i "s#$image@sha256:[0-9a-f]\{64\}#$image@$bad#" $manifests
git add $manifests
git commit -q -m "sl-ledger: promote ${bad:7:12} (rollback drill)"
git push -q origin main; refresh
log "T0 promotion pushed: $(git rev-parse --short HEAD)"
until [ "$(deployed)" = "$image@$bad" ]; do sleep 1; done; log "   applied by ArgoCD"
until settled; do sleep 1; done; log "T1 rollout complete"
from=$(smoke_lines)
until kubectl -n sl-ledger logs smoke | tail -n +$((from + 1)) | grep -qv " 201 "; do sleep 1; done
log "T2 detected: $(kubectl -n sl-ledger logs smoke | tail -n +$((from + 1)) | grep -v ' 201 ' | head -1)"
git revert --no-edit HEAD
git push -q origin main; refresh
log "T3 revert pushed: $(git rev-parse --short HEAD)"
until [ "$(deployed)" = "$image@$good" ]; do sleep 1; done; log "   applied by ArgoCD"
until settled; do sleep 1; done; log "T4 rollback complete"
from=$(smoke_lines)
until kubectl -n sl-ledger logs smoke | tail -n +$((from + 1)) | grep -q " 201 "; do sleep 1; done
log "T5 first good booking: $(kubectl -n sl-ledger logs smoke | tail -n +$((from + 1)) | grep ' 201 ' | head -1)"
