#!/usr/bin/env bash
# Uploads the demo policies as brand-new documents (isNew=true), with procurement at version 1.
#
#   TOKEN=$(scripts/dev-token.sh mike) demo/load-documents.sh [base-url]
#
# Run it once against an empty database. Running it again gets a 409 for each document,
# which is the duplicate-name protection doing its job.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
DOCS="$(cd "$(dirname "$0")" && pwd)/documents"
: "${TOKEN:?Set TOKEN first: TOKEN=\$(scripts/dev-token.sh mike)}"

upload() { # name file
    echo "== $1"
    curl -s -w "\n   HTTP %{http_code}\n" -X POST "$BASE_URL/api/v1/documents" \
        -H "Authorization: Bearer $TOKEN" \
        -F "file=@$DOCS/$2" -F "name=$1" -F "isNew=true"
}

upload procurement-policy    procurement-policy-v1.txt
upload travel-expense-policy travel-expense-policy.txt
upload hazmat-shipping-policy hazmat-shipping-policy.txt
upload it-equipment-policy   it-equipment-data-policy.txt
