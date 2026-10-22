#!/usr/bin/env bash
# Registers the rows this checkout adds to schema-subjects.tsv into the throwaway registry.
# Seed + check read the list from <git-ref>, so without this a new row is first registered
# by the schema-sync-hook after merge - a typo'd file/subject, a schema the registry can't
# parse, or an import with no library row would only fail there (#857).
#
# Additions only: removals and the "# reset:" / "# compatibility:" directives still come
# from <git-ref>. Run after check-all-schemas.sh, so that check compares against the
# base's versions only. Registering still enforces the level set by seed-registry.sh.
#
# Usage: register-added-schemas.sh <git-ref>
# Example: register-added-schemas.sh origin/main
set -euo pipefail

BASE_REF="${1:?usage: register-added-schemas.sh <git-ref>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
PROTO_DIR="${REPO_ROOT}/shared/proto"
REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8081/apis/ccompat/v6}"

source "${SCRIPT_DIR}/_load-schema-policy.sh"

if [ -z "$ADDED_ROWS" ]; then
  echo "No rows added to schema-subjects.tsv relative to ${BASE_REF} - nothing to register."
  exit 0
fi

# Keeps going after a failure, so one run surfaces every broken row.
FAILED=0
while IFS=$'\t' read -r FILE SUBJECT; do
  [ -n "$FILE" ] || continue
  echo "=== ${SUBJECT:-<no subject>} (${FILE}): added in this PR ==="
  if [ -z "$SUBJECT" ]; then
    echo "Row '${FILE}' has no subject - expected <file><TAB><subject>."
    FAILED=1
  elif [ ! -f "${PROTO_DIR}/${FILE}" ]; then
    echo "'${FILE}' not found under shared/proto."
    FAILED=1
  elif ! SCHEMA_REGISTRY_URL="$REGISTRY_URL" bash "${SCRIPT_DIR}/register-schema.sh" "${PROTO_DIR}/${FILE}" "${SUBJECT}"; then
    FAILED=1
  fi
  echo
done <<< "$ADDED_ROWS"

if [ "$FAILED" -ne 0 ]; then
  echo "One or more rows added to schema-subjects.tsv would fail to register after merge - see above."
  exit 1
fi
echo "All added rows registered."
