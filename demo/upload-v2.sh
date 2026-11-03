#!/usr/bin/env bash
# Supersedes procurement-policy version 1 with version 2 (isNew=false).
# upsert_document() locks the active row, deactivates it and inserts version 2 in one transaction.
#
#   TOKEN=$(scripts/dev-token.sh mike) demo/upload-v2.sh [base-url]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
DOCS="$(cd "$(dirname "$0")" && pwd)/documents"
: "${TOKEN:?Set TOKEN first: TOKEN=\$(scripts/dev-token.sh mike)}"

curl -s -w "\nHTTP %{http_code}\n" -X POST "$BASE_URL/api/v1/documents" \
    -H "Authorization: Bearer $TOKEN" \
    -F "file=@$DOCS/procurement-policy-v2.txt" -F "name=procurement-policy" -F "isNew=false"
