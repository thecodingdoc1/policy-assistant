#!/usr/bin/env bash
# Sends every question in eval/questions.tsv to the chat endpoint and saves the raw responses
# (answer + sources) for grading against eval/EVAL_SET.md.
#
#   TOKEN=$(scripts/dev-token.sh eval) eval/run-eval.sh [base-url]
#
# Each question is sent without a conversationId, so it is answered with no chat history:
# earlier questions can't influence later ones.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/results/run-$(date +%Y%m%d-%H%M%S).jsonl"
: "${TOKEN:?Set TOKEN first: TOKEN=\$(scripts/dev-token.sh eval)}"
mkdir -p "$DIR/results"

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

while IFS=$'\t' read -r id question; do
    [ -z "$id" ] && continue
    q=$(json_escape "$question")
    response=$(curl -s -m 300 -X POST "$BASE_URL/api/v1/chat" \
        -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
        -d "{\"prompt\":\"$q\"}")
    printf '{"id":"%s","question":"%s","response":%s}\n' "$id" "$q" "${response:-null}" >> "$OUT"
    echo "$id done" >&2
done < "$DIR/questions.tsv"

echo "$OUT"
