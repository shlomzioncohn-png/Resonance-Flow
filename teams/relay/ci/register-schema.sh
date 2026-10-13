#!/usr/bin/env bash
# Actually registers a new schema version — run this only on merge/deploy,
# after check-schema-compatibility.sh has already passed. Never run this on a PR.
#
# Usage: register-schema.sh <path-to-.proto-file> <subject-name>
# Example: register-schema.sh shared/proto/task-created-event.proto task.created-value
set -euo pipefail

SCHEMA_FILE="$1"
SUBJECT="$2"
REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8081/apis/ccompat/v6}"

source "$(dirname "${BASH_SOURCE[0]}")/_schema-references.sh"

BODY=$(jq -n --arg schema "$(cat "$SCHEMA_FILE")" --argjson refs "$REFERENCES" \
  '{schema: $schema, schemaType: "PROTOBUF", references: $refs}')

HTTP_STATUS=$(curl -s -o /tmp/register-response.json -w "%{http_code}" \
  -X POST "${REGISTRY_URL}/subjects/${SUBJECT}/versions" \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d "$BODY")

if [ "$HTTP_STATUS" -ge 400 ]; then
  echo "Failed to register schema for '${SUBJECT}' (HTTP $HTTP_STATUS):"
  cat /tmp/register-response.json
  exit 1
fi

GLOBAL_ID=$(jq -r '.id' /tmp/register-response.json)
echo "Registered '${SUBJECT}' — global schema id ${GLOBAL_ID}"
