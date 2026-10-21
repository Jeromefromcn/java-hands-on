#!/bin/sh
# One image, three environments, no rebuild. Stage 14.
#
#   set -a; . infra/.env; set +a                     # Compose services running
#   infra/delivery/three-envs.sh <image>             # output: target/evidence/14-three-envs.txt
#
# Starts the same image three times with infra/delivery/envs/{dev,staging,prod}.env (prod also with
# envs/prod-config mounted as /app/config) and the secrets from this shell's environment, then reads
# what each one says about itself: the image id it runs, /actuator/info, the active profile, the
# management port, the log format and the Hikari pool size (from /actuator/prometheus).
set -eu
image=${1:?usage: three-envs.sh <image>}
cd "$(dirname "$0")/../.."
out=target/evidence/14-three-envs.txt
# Each environment has its own database in the Compose Postgres: environments share an image, never
# state. (They first shared one: the local profile had applied its seed migrations, and the prod
# profile's Flyway refused a schema with migrations it does not have.)
for db in ledger_staging ledger_prod; do
  docker compose -f infra/compose.yaml exec -T postgres sh -c \
    "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tAc \"SELECT 1 FROM pg_database WHERE datname = '$db'\" | grep -q 1 \
     || psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -qc 'CREATE DATABASE $db'"
done
export SPRING_DATASOURCE_USERNAME="$POSTGRES_USER" SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export LEDGER_DB_URL="jdbc:postgresql://127.0.0.1:5432/${POSTGRES_DB:?source infra/.env}"
export LEDGER_DB_USER="$POSTGRES_USER" LEDGER_DB_PASSWORD="$POSTGRES_PASSWORD"
export LEDGER_REDIS_PASSWORD="$REDIS_PASSWORD"
token=$(infra/delivery/kc-token.sh ops1)
metrics_token=$(curl -fsS http://127.0.0.1:8180/realms/ledger/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=prometheus \
  --data-urlencode "client_secret=${PROMETHEUS_CLIENT_SECRET:?source infra/.env}" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
trap 'for e in dev staging prod; do docker rm -f sl-ledger-$e >/dev/null 2>&1; done' EXIT

mkdir -p "$(dirname "$out")"
{
  echo "One image, three environments: $image ($(docker image inspect "$image" --format '{{.Id}}' | cut -c1-19))"
  echo
  for env in dev staging prod; do
    mount=""
    [ "$env" = prod ] && mount="-v $PWD/infra/delivery/envs/prod-config:/app/config:ro"
    # dev's local profile names its database (LEDGER_DB_URL); the prod profile names none. A
    # SPRING_DATASOURCE_URL given to dev would win over the profile file, so it is not given.
    database=""
    [ "$env" != dev ] && database="-e SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/ledger_$env"
    # shellcheck disable=SC2086
    docker run -d --name "sl-ledger-$env" --network host --env-file "infra/delivery/envs/$env.env" \
      $database -e SPRING_DATASOURCE_USERNAME -e SPRING_DATASOURCE_PASSWORD \
      -e LEDGER_DB_URL -e LEDGER_DB_USER -e LEDGER_DB_PASSWORD -e LEDGER_REDIS_PASSWORD \
      $mount "$image" >/dev/null
    port=$(sed -n 's/^SERVER_PORT=//p' "infra/delivery/envs/$env.env")
    mport=$(sed -n 's/^MANAGEMENT_SERVER_PORT=//p' "infra/delivery/envs/$env.env")
    mport=${mport:-$port}
    until curl -fsS -o /dev/null "localhost:$mport/actuator/health/readiness" 2>/dev/null; do
      [ "$(docker inspect -f '{{.State.Running}}' "sl-ledger-$env")" = true ] \
        || { docker logs "sl-ledger-$env" 2>&1 | tail -20; exit 1; }
      sleep 1
    done
    echo "== $env (--env-file infra/delivery/envs/$env.env${mount:+, config/ mounted})"
    echo "image id:        $(docker inspect "sl-ledger-$env" --format '{{.Image}}' | cut -c1-19)"
    echo "/actuator/info:  $(curl -fsS -H "Authorization: Bearer $token" "localhost:$mport/actuator/info")"
    echo "profile:         $(docker logs "sl-ledger-$env" 2>&1 | grep -o 'profile is active: [^,]*' | head -1 | tr -d '\\"')"
    echo "API / management port: $port / $mport"
    first=$(docker logs "sl-ledger-$env" 2>/dev/null | grep -m1 'Started SlLedgerApplication')
    case "$first" in \{*) echo "log format:      JSON (one object per line)";; *) echo "log format:      text pattern";; esac
    echo "Hikari pool max: $(curl -fsS -H "Authorization: Bearer $metrics_token" "localhost:$mport/actuator/prometheus" \
      | grep '^hikaricp_connections_max' | awk '{print $NF}')"
    echo
  done
} | tee "$out"
