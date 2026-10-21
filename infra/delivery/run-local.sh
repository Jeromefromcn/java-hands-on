#!/bin/sh
# Runs an image of the ledger against infra/compose.yaml's services, in the background. Stage 14.
#
#   set -a; . infra/.env; set +a
#   infra/delivery/run-local.sh <image> [container-name] [port] [extra docker run args...]
#
# Host network, so the local profile's 127.0.0.1 endpoints are the Compose ports; the HTTP port is
# moved off 8080 (taken on this machine) by SERVER_PORT, which is itself the point: the image does
# not know which port it serves on until the environment says. Secrets are passed by name, so their
# values never appear on a command line.
set -eu
image=${1:?usage: run-local.sh <image> [name] [port] [docker args...]}
name=${2:-sl-ledger-local}
port=${3:-8090}
[ $# -ge 3 ] && shift 3 || shift $#
export LEDGER_DB_URL="jdbc:postgresql://127.0.0.1:5432/${POSTGRES_DB:?source infra/.env}"
export LEDGER_DB_USER="$POSTGRES_USER" LEDGER_DB_PASSWORD="$POSTGRES_PASSWORD"
export LEDGER_REDIS_PASSWORD="$REDIS_PASSWORD"
docker rm -f "$name" >/dev/null 2>&1 || true
docker run -d --name "$name" --network host \
  -e SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}" -e SERVER_PORT="$port" \
  -e LEDGER_DB_URL -e LEDGER_DB_USER -e LEDGER_DB_PASSWORD -e LEDGER_REDIS_PASSWORD \
  "$@" "$image"
