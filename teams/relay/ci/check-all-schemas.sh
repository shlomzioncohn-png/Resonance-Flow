#!/usr/bin/env bash
# Dry-run compatibility check for every schema in schema-subjects.tsv against
# whatever SCHEMA_REGISTRY_URL points at. In CI that registry is the throwaway
# one that seed-registry.sh just seeded from the PR's base branch, so this is
# effectively "does the PR break any consumer?" — answered before merge.
#
# Registers nothing. Checks every schema before failing, so one PR run surfaces
# every incompatibility at once.
#
# Usage: check-all-schemas.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
PROTO_DIR="${REPO_ROOT}/teams/relay/src/main/proto"
SUBJECTS_TSV="${SCRIPT_DIR}/schema-subjects.tsv"

FAILED=0
while IFS=$'\t' read -r FILE SUBJECT || [ -n "$FILE" ]; do
  case "$FILE" in ''|'#'*) continue ;; esac
  echo "=== ${SUBJECT} (${FILE}) ==="
  if ! bash "${SCRIPT_DIR}/check-schema-compatibility.sh" "${PROTO_DIR}/${FILE}" "${SUBJECT}"; then
    FAILED=1
  fi
  echo
done < "$SUBJECTS_TSV"

if [ "$FAILED" -ne 0 ]; then
  echo "One or more schema changes break ${COMPATIBILITY_LEVEL:-FULL} compatibility — see above."
  exit 1
fi
echo "All schemas compatible."
