#!/usr/bin/env bash
# Seeds a Schema Registry with the schema versions as they exist on a given git
# ref (in CI: the PR's base branch, e.g. the merge target's commit). That gives
# the pre-merge compatibility check something to compare the PR's schemas
# against — the "previous, known-good schema from Git" — with no live cluster.
#
# The schema list AND the compatibility level also come from <git-ref>
# (schema-subjects.tsv on that ref), not from this checkout — so a PR can't
# weaken its own gate. Reuses register-schema.sh for the actual registration.
# Exception: a library row this checkout adds is seeded from the checkout (#857).
# Pairs with check-all-schemas.sh. Driven by .github/workflows/relay-schema-ci.yaml.
#
# Usage: seed-registry.sh <git-ref>
# Example: seed-registry.sh origin/relay-team
set -euo pipefail

BASE_REF="${1:?usage: seed-registry.sh <git-ref>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
PROTO_DIR="shared/proto"
# where the schemas lived before shared/proto - for base refs predating the move
LEGACY_PROTO_DIR="teams/relay/src/main/proto"
REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8081/apis/ccompat/v6}"

# Schema list + compatibility level, read from BASE_REF (not this PR).
source "${SCRIPT_DIR}/_load-schema-policy.sh"
source "${SCRIPT_DIR}/_canonicalize.sh"

# Apicurio enforces NO compatibility rule by default (level NONE), so the checks
# would silently always pass. Set the level (from the base branch's tsv) first.
echo "Setting global compatibility level to ${COMPATIBILITY_LEVEL}"
curl -sf -X PUT "${REGISTRY_URL}/config" \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d "{\"compatibility\": \"${COMPATIBILITY_LEVEL}\"}" > /dev/null

TMP_DIR="$(mktemp -d)"
CANON_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR" "$CANON_DIR"' EXIT

# "# reset:" subjects aren't seeded, so the check sees them as new - mirrors the hook's hard delete.
while read -r RESET; do
  [ -n "$RESET" ] || continue
  echo "=== ${RESET}: marked '# reset:' on ${BASE_REF} — not seeded ==="
  SCHEMA_LIST="$(printf '%s\n' "$SCHEMA_LIST" | awk -F'\t' -v s="$RESET" '{ sub(/\r$/, "") } $2 != s')"
done <<< "$RESET_SUBJECTS"

while IFS=$'\t' read -r FILE SUBJECT || [ -n "$FILE" ]; do
  case "$FILE" in ''|'#'*) continue ;; esac

  BASE_FILE=""
  for DIR in "$PROTO_DIR" "$LEGACY_PROTO_DIR"; do
    if git -C "$REPO_ROOT" cat-file -e "${BASE_REF}:${DIR}/${FILE}" 2>/dev/null; then
      BASE_FILE="${DIR}/${FILE}"
      break
    fi
  done

  if [ -n "$BASE_FILE" ]; then
    git -C "$REPO_ROOT" show "${BASE_REF}:${BASE_FILE}" > "${TMP_DIR}/${FILE}"
    echo "=== seeding ${SUBJECT} from ${BASE_REF} ==="
    canonicalize "$TMP_DIR" "$FILE" "${CANON_DIR}/${FILE}"
    SCHEMA_REGISTRY_URL="$REGISTRY_URL" bash "${SCRIPT_DIR}/register-schema.sh" "${CANON_DIR}/${FILE}" "${SUBJECT}"
  elif [ "$FILE" = "$SUBJECT" ] && [ -f "${REPO_ROOT}/${PROTO_DIR}/${FILE}" ] \
       && grep -qxF "${FILE}"$'\t'"${SUBJECT}" <<< "$ADDED_ROWS"; then
    # No base version to protect, and a base schema the PR makes import it can't resolve otherwise.
    echo "=== ${SUBJECT}: library added in this checkout — seeding its checkout version ==="
    cp "${REPO_ROOT}/${PROTO_DIR}/${FILE}" "${TMP_DIR}/${FILE}"
    canonicalize "$TMP_DIR" "$FILE" "${CANON_DIR}/${FILE}"
    SCHEMA_REGISTRY_URL="$REGISTRY_URL" bash "${SCRIPT_DIR}/register-schema.sh" "${CANON_DIR}/${FILE}" "${SUBJECT}"
  else
    echo "=== ${SUBJECT}: ${FILE} does not exist on ${BASE_REF} — new schema, nothing to seed ==="
  fi
done <<< "$SCHEMA_LIST"
