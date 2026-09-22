#!/usr/bin/env bash
# Seeds a Schema Registry with the schema versions as they exist on a given git
# ref (in CI: the PR's base branch, e.g. the merge target's commit). That gives
# the pre-merge compatibility check something to compare the PR's schemas
# against — the "previous, known-good schema from Git" — with no live cluster.
#
# Reuses register-schema.sh for the actual registration. Pairs with
# check-all-schemas.sh. Driven by .github/workflows/relay-schema-ci.yaml.
#
# Usage: seed-registry.sh <git-ref>
# Example: seed-registry.sh origin/main
set -euo pipefail

BASE_REF="${1:?usage: seed-registry.sh <git-ref>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
PROTO_DIR="teams/relay/src/main/proto"
SUBJECTS_TSV="${SCRIPT_DIR}/schema-subjects.tsv"
REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8081/apis/ccompat/v6}"
COMPATIBILITY_LEVEL="${COMPATIBILITY_LEVEL:-FULL}"

# Apicurio enforces NO compatibility rule by default (level NONE), so the checks
# would silently always pass. Set the level explicitly and globally first.
# FULL = both BACKWARD and FORWARD: producers and consumers evolve schemas
# independently here, so a change must be safe in both directions.
echo "Setting global compatibility level to ${COMPATIBILITY_LEVEL}"
curl -sf -X PUT "${REGISTRY_URL}/config" \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d "{\"compatibility\": \"${COMPATIBILITY_LEVEL}\"}" > /dev/null

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

while IFS=$'\t' read -r FILE SUBJECT || [ -n "$FILE" ]; do
  case "$FILE" in ''|'#'*) continue ;; esac

  if git -C "$REPO_ROOT" cat-file -e "${BASE_REF}:${PROTO_DIR}/${FILE}" 2>/dev/null; then
    git -C "$REPO_ROOT" show "${BASE_REF}:${PROTO_DIR}/${FILE}" > "${TMP_DIR}/${FILE}"
    echo "=== seeding ${SUBJECT} from ${BASE_REF} ==="
    SCHEMA_REGISTRY_URL="$REGISTRY_URL" bash "${SCRIPT_DIR}/register-schema.sh" "${TMP_DIR}/${FILE}" "${SUBJECT}"
  else
    echo "=== ${SUBJECT}: ${FILE} does not exist on ${BASE_REF} — new schema, nothing to seed ==="
  fi
done < "$SUBJECTS_TSV"
