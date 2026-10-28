#!/usr/bin/env bash
# Dry-run compatibility check — does NOT register anything.
# Run this on every Pull Request, before build/deploy, so an incompatible schema
# change fails fast instead of reaching production.
#
# Usage: check-schema-compatibility.sh <path-to-.proto-file> <subject-name>
# Example: check-schema-compatibility.sh shared/proto/task-created-event.proto task.created-value
set -euo pipefail

SCHEMA_FILE="$1"
SUBJECT="$2"
REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8081/apis/ccompat/v6}"

# Sets REFERENCES and BODY.
source "$(dirname "${BASH_SOURCE[0]}")/_schema-references.sh"

RESPONSE="$(mktemp)"
trap 'rm -f "$RESPONSE"' EXIT

HTTP_STATUS=$(curl -s -o "$RESPONSE" -w "%{http_code}" \
  -X POST "${REGISTRY_URL}/compatibility/subjects/${SUBJECT}/versions/latest" \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d "$BODY")

if [ "$HTTP_STATUS" = "404" ]; then
  echo "New subject '${SUBJECT}' — nothing registered yet to compare against, passing."
  exit 0
fi

if [ "$HTTP_STATUS" -ge 400 ]; then
  echo "Registry request failed (HTTP $HTTP_STATUS):"
  cat "$RESPONSE"
  exit 1
fi

if [ "$(jq -r '.is_compatible' "$RESPONSE")" != "true" ]; then
  echo "Schema change for '${SUBJECT}' breaks compatibility:"
  cat "$RESPONSE"
  exit 1
fi

echo "Compatible: '${SUBJECT}' can move to this schema version."
