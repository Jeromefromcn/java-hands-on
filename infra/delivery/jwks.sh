#!/bin/sh
# Prints the JWK set of an RSA public key, kid "k3s-1", for infra/delivery/token.sh's tokens.
#
#   infra/delivery/jwks.sh <public-key.pem>
set -eu
pub=${1:?usage: jwks.sh <public.pem>}
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
n=$(openssl rsa -pubin -in "$pub" -noout -modulus | sed 's/^Modulus=//' | xxd -r -p | b64url)
# The public exponent, 65537 in every key openssl generates by default: 0x010001.
e=$(openssl rsa -pubin -in "$pub" -noout -text | sed -n 's/^Exponent: \([0-9]*\).*/\1/p')
[ "$e" = 65537 ] || { echo "unexpected exponent $e" >&2; exit 1; }
jq -cn --arg n "$n" '{keys: [{kty: "RSA", use: "sig", alg: "RS256", kid: "k3s-1", n: $n, e: "AQAB"}]}'
