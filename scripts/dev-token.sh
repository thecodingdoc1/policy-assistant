#!/usr/bin/env bash
# Mints a signed RS256 JWT for local development and demos.
#
#   scripts/dev-token.sh [subject] [ttl-seconds]
#   TOKEN=$(scripts/dev-token.sh mike)
#
# On first run it creates an RSA keypair in dev-keys/ (git-ignored). The app verifies tokens
# with dev-keys/public.pem (see spring.security.oauth2.resourceserver.jwt.public-key-location);
# only this script ever touches private.pem. The token's "sub" claim becomes created_by /
# deactivated_by on uploaded documents.
#
# Requires openssl (bundled with Git for Windows).
set -euo pipefail

SUBJECT="${1:-dev-user}"
TTL="${2:-3600}"
KEY_DIR="${JTMS_DEV_KEY_DIR:-$(cd "$(dirname "$0")/.." && pwd)/dev-keys}"
PRIVATE_KEY="$KEY_DIR/private.pem"
PUBLIC_KEY="$KEY_DIR/public.pem"

if [ ! -f "$PRIVATE_KEY" ]; then
    mkdir -p "$KEY_DIR"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY" 2>/dev/null
    openssl rsa -pubout -in "$PRIVATE_KEY" -out "$PUBLIC_KEY" 2>/dev/null
    echo "Created a new keypair in $KEY_DIR (restart the app so it loads the new public key)" >&2
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

NOW=$(date +%s)
HEADER=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
PAYLOAD=$(printf '{"iss":"jtms-dev","sub":"%s","iat":%d,"exp":%d}' "$SUBJECT" "$NOW" "$((NOW + TTL))" | b64url)
SIGNATURE=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -sign "$PRIVATE_KEY" -binary | b64url)

echo "$HEADER.$PAYLOAD.$SIGNATURE"
