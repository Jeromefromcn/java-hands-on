#!/bin/sh
# Prints an access token for one of the Compose Keycloak's synthetic users (stage 13's realm).
#
#   set -a; . infra/.env; set +a
#   infra/delivery/kc-token.sh ops1
#
# The password comes from KEYCLOAK_USER_PASSWORD in the environment, never from an argument.
set -eu
user=${1:?usage: kc-token.sh <user>}
curl -fsS http://127.0.0.1:8180/realms/ledger/protocol/openid-connect/token \
  -d grant_type=password -d client_id=ledger-cli -d "username=$user" \
  --data-urlencode "password=${KEYCLOAK_USER_PASSWORD:?set KEYCLOAK_USER_PASSWORD}" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
