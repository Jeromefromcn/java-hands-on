#!/bin/sh
# Signs a test access token for an environment whose identity provider is a static key set (the
# K3s deployment, stage 14). Prints the token.
#
#   infra/delivery/token.sh <private-key.pem> <issuer> <subject> <role[,role...]> [counterparty] [ttl-s]
#
# RS256, kid "k3s-1", audience sl-ledger; roles in realm_access.roles and the counterparty in
# counterparty_id, the claims stage 13 reads from Keycloak's tokens. The private key never enters a
# repository: it lives where the caller keeps it, and only the public half is published, by
# infra/delivery/jwks.sh. Default lifetime one hour.
set -eu
key=${1:?usage: token.sh <key.pem> <issuer> <subject> <roles> [counterparty] [ttl-seconds]}
issuer=${2:?issuer}
subject=${3:?subject}
roles=${4:?roles}
counterparty=${5:-}
ttl=${6:-3600}
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT","kid":"k3s-1"}' | b64url)
payload=$(jq -cn --arg iss "$issuer" --arg sub "$subject" --arg roles "$roles" --arg cp "$counterparty" \
  --argjson iat "$now" --argjson exp "$((now + ttl))" \
  '{iss: $iss, sub: $sub, aud: "sl-ledger", iat: $iat, exp: $exp,
    realm_access: {roles: ($roles | split(","))}}
   + (if $cp == "" then {} else {counterparty_id: $cp} end)' | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$key" -binary | b64url)
printf '%s.%s.%s\n' "$header" "$payload" "$signature"
