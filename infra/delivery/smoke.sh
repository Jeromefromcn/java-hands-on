#!/bin/sh
# A smoke check that books, inside the K3s namespace: every 5 s it opens a position through the
# Service and logs the time and the status (201 is healthy). Stage 14's rollback drill detects a
# bad release with it and confirms the rollback with it.
#
#   infra/delivery/smoke.sh start <private-key.pem>   # starts pod "smoke" (token valid 2 h)
#   infra/delivery/smoke.sh log                       # its lines so far
#   infra/delivery/smoke.sh stop
set -eu
cd "$(dirname "$0")/../.."
ns=sl-ledger
case "${1:-}" in
  start)
    token=$(infra/delivery/token.sh "${2:?key}" https://sl-ledger.k3s.invalid smoke-check ledger-trader "" 7200)
    kubectl -n "$ns" delete pod smoke --ignore-not-found >/dev/null
    kubectl -n "$ns" run smoke --restart=Never --image=curlimages/curl:8.16.0 --env="TOKEN=$token" \
      --command -- sh -c 'while true; do
        code=$(curl -s -o /tmp/b -w "%{http_code}" -m 10 -X POST http://sl-ledger:8080/loans \
          -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
          -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
          -d "{\"counterpartyId\":\"CP-001\",\"isin\":\"HK0000000007\",\"quantity\":10,\"collateral\":{\"amount\":\"500.00\",\"currency\":\"HKD\"},\"rule\":{\"type\":\"FLAT\",\"rate\":\"0.0365\"},\"tradeDate\":\"2026-10-15\"}")
        echo "$(date -u +%H:%M:%S) $code $(sed -E "s/.*\"type\":\"urn:sl-ledger:problem:([^\"]+)\".*/\1/; s/^\{\"positionId\".*/booked/" /tmp/b | head -c 60)"
        sleep 5
      done' 2>&1 | grep -v PodSecurity ;;
  log) kubectl -n "$ns" logs smoke ;;
  stop) kubectl -n "$ns" delete pod smoke --ignore-not-found ;;
  *) echo "usage: smoke.sh start <key>|log|stop" >&2; exit 2 ;;
esac
