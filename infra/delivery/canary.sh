#!/bin/bash
# The day-count rollout on the K3s ledger, end to end. Stage 14.
#
#   infra/delivery/canary.sh <private-key.pem> <first-business-date> <next-business-date>
#
# Adds 40 synthetic counterparties (CP-K001..CP-K040) to the cluster's database and opens one USD
# position for each through the API; raises the rollout to 5% (a maker, a refused self-approval, a
# checker); shows a further raise refused inside the observation period; runs the accrual for the
# first date; kills the rollout; runs the accrual for the next date. Output:
# target/evidence/14-canary.txt. Light on the cluster: two short Jobs and some API calls.
set -euo pipefail
req=$(mktemp)
trap 'rm -f "$req"' EXIT
key=${1:?usage: canary.sh <key.pem> <date1> <date2>}
date1=${2:?date1}
date2=${3:?date2}
cd "$(dirname "$0")/../.."
ns=sl-ledger
issuer=https://sl-ledger.k3s.invalid
flag=accrual.day-count-by-market
out=target/evidence/14-canary.txt
mkdir -p "$(dirname "$out")"
trader=$(infra/delivery/token.sh "$key" "$issuer" trader-a ledger-trader)
ops_a=$(infra/delivery/token.sh "$key" "$issuer" ops-a ledger-ops)
ops_b=$(infra/delivery/token.sh "$key" "$issuer" ops-b ledger-ops)

psql() { kubectl -n "$ns" exec -i postgres-0 -- sh -c 'psql -U "$POSTGRES_USER" -d ledger -qAt' ; }
# One curl pod for a batch of requests; each line of stdin is "label|method|path|token|body".
api() {
  kubectl -n "$ns" run "canary-$RANDOM" --rm -i --restart=Never --quiet --image=curlimages/curl:8.16.0 \
    --command -- sh -c 'while IFS="|" read -r label method path token body; do
      printf "%-34s %-5s %-46s -> " "$label" "$method" "$path"
      if [ -n "$body" ]; then
        curl -s -o /tmp/b -w "%{http_code} " -X "$method" -H "Authorization: Bearer $token" \
          -H "Content-Type: application/json" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
          -d "$body" "http://sl-ledger:8080$path"
      else
        curl -s -o /tmp/b -w "%{http_code} " -X "$method" -H "Authorization: Bearer $token" "http://sl-ledger:8080$path"
      fi
      sed -E "s/.*\"type\":\"urn:sl-ledger:problem:([^\"]+)\".*/\1/; s/^\{\"id\":([0-9]+).*/change \1/; s/^\{\"flag\":\"[^\"]*\",\"percent\":([0-9]+).*/percent \1/; s/^\{\"positionId\":\"([^\"]+)\".*/opened \1/" /tmp/b | head -c 120; echo
    done' 2> >(grep -v PodSecurity >&2)
}
accrual() { # date
  local job="accrual-canary-${1//-/}"
  kubectl -n "$ns" delete job "$job" --ignore-not-found >/dev/null
  kubectl -n "$ns" create job "$job" --from=cronjob/sl-ledger-accrual --dry-run=client -o json \
    | python3 -c "import json,sys; j=json.load(sys.stdin); c=j['spec']['template']['spec']['containers'][0]; c['env'].append({'name':'LEDGER_BATCH_COMMAND_DATE','value':'$1'}); print(json.dumps(j))" \
    | kubectl create -f - >/dev/null
  kubectl -n "$ns" wait --for=condition=complete "job/$job" --timeout=300s >/dev/null
  echo "accrual Job $job for $1: $(kubectl -n "$ns" logs "job/$job" | grep -o '"message":"job=dailyAccrual status=[A-Z]*[^"]*' | sed 's/"message":"//' | cut -c1-150)"
}
fees() { # date
  psql <<SQL
SELECT p.counterparty_id || ' ' || s.currency || ' ' || e.fee_amount::numeric(10,2) || ' ' || e.fee_currency
FROM accrual_entry e JOIN loan_position p ON p.id = e.position_id JOIN security s ON s.isin = p.isin
WHERE e.business_date = '$1' AND p.counterparty_id LIKE 'CP-K%' ORDER BY p.counterparty_id;
SQL
}

{
  echo "Day-count rollout on the K3s ledger, $(date -u +%FT%TZ). Flag $flag; observation period 10 min (LEDGER_ROLLOUT_OBSERVATION)."
  echo
  echo "== 40 synthetic counterparties, one USD position each (US0000000010, 1,000 shares at 10.00, flat 3.65%)"
  psql <<'SQL'
INSERT INTO counterparty (id, name, credit_limit_amount, credit_limit_currency)
SELECT 'CP-K' || lpad(n::text, 3, '0'), 'Synthetic Canary Borrower ' || n, 1000000.00, 'USD'
FROM generate_series(1, 40) n ON CONFLICT (id) DO NOTHING;
SQL
  for i in $(seq -w 1 40); do
    echo "open CP-K0$i|POST|/loans|$trader|{\"counterpartyId\":\"CP-K0$i\",\"isin\":\"US0000000010\",\"quantity\":1000,\"collateral\":{\"amount\":\"15000.00\",\"currency\":\"USD\"},\"rule\":{\"type\":\"FLAT\",\"rate\":\"0.0365\"},\"tradeDate\":\"2026-10-15\"}"
  done | api | awk '{print $NF == "" ? $0 : $0}' | grep -c " 201 " | sed 's/^/positions opened: /'
  echo
  echo "== raising to 5%"
  printf '%s\n' \
    "ops-a proposes 5%|POST|/rollouts/$flag/raises|$ops_a|{\"toPercent\":5}" > "$req"
  first=$(api < "$req"); echo "$first"; id=$(echo "$first" | sed -E 's/.*change ([0-9]+).*/\1/')
  printf '%s\n' \
    "ops-a approves own change $id|POST|/rollouts/$flag/raises/$id/approval|$ops_a|" \
    "ops-b approves change $id|POST|/rollouts/$flag/raises/$id/approval|$ops_b|" \
    "ops-a proposes 25%|POST|/rollouts/$flag/raises|$ops_a|{\"toPercent\":25}" > "$req"
  second=$(api < "$req"); echo "$second"; id2=$(echo "$second" | tail -1 | sed -E 's/.*change ([0-9]+).*/\1/')
  printf '%s\n' "ops-b approves change $id2 at once|POST|/rollouts/$flag/raises/$id2/approval|$ops_b|" > "$req"
  api < "$req"
  echo
  echo "== which counterparties 5% selects: the USD fees booked for $date1 (1.01 = ACT/360, 1.00 = ACT/365F)"
  accrual "$date1"
  fees "$date1" | awk '{print "  " $0}' | awk '/1\.01/ || NR <= 3'
  echo "  ($(fees "$date1" | grep -c ' 1.01 ') at 1.01, $(fees "$date1" | grep -c ' 1.00 ') at 1.00)"
  echo
  echo "== the kill switch, one person"
  printf '%s\n' "ops-a lowers to 0%|POST|/rollouts/$flag/lowering|$ops_a|{\"toPercent\":0}" > "$req"
  api < "$req"
  accrual "$date2"
  echo "  $date2: $(fees "$date2" | grep -c ' 1.01 ') at 1.01, $(fees "$date2" | grep -c ' 1.00 ') at 1.00"
  echo "  $date1 is unchanged: $(fees "$date1" | grep -c ' 1.01 ') at 1.01 (booked is booked)"
  echo
  echo "== what each run recorded (job execution context, Java-serialized and base64-encoded by Spring Batch)"
  psql <<'SQL' | python3 -c '
import base64, struct, sys
for line in sys.stdin:
    date, ctx = line.strip().split("|")
    raw = base64.b64decode(ctx)
    at = raw.index(b"accrual.dayCountRolloutPercent")
    xp = raw.index(b"xp", raw.index(b"java.lang.Number", at))
    print(f"  businessDate {date}: accrual.dayCountRolloutPercent = {struct.unpack(\">i\", raw[xp + 2:xp + 6])[0]}")'
SELECT p.parameter_value || '|' || c.short_context
FROM batch_job_execution e
JOIN batch_job_execution_params p ON p.job_execution_id = e.job_execution_id AND p.parameter_name = 'businessDate'
JOIN batch_job_execution_context c ON c.job_execution_id = e.job_execution_id
ORDER BY e.job_execution_id;
SQL
  echo
  echo "== audit trail of the rollout"
  psql <<'SQL'
SELECT actor || ' ' || result || ' ' || reason FROM audit_event WHERE action = 'ROLLOUT' ORDER BY id;
SQL
} | tee "$out"
