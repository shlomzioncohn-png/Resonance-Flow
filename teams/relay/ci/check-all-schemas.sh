#!/usr/bin/env bash
# Dry-run compatibility check for every schema against whatever SCHEMA_REGISTRY_URL
# points at. In CI that registry is the throwaway one that seed-registry.sh just
# seeded from the PR's base branch, so this is effectively "does the PR break any
# consumer?" — answered before merge.
#
# The schema list is read from <git-ref> (schema-subjects.tsv on that ref), not
# from this checkout — so a PR can't drop a schema from the check. The .proto
# CONTENT being checked is this checkout's (the PR's) version — that's the point.
#
# Registers nothing. Checks every schema before failing, so one PR run surfaces
# every incompatibility at once.
#
# Usage: check-all-schemas.sh <git-ref>
# Example: check-all-schemas.sh origin/relay-team
set -euo pipefail

BASE_REF="${1:?usage: check-all-schemas.sh <git-ref>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
PROTO_DIR="${REPO_ROOT}/shared/proto"

# Schema list + compatibility level, read from BASE_REF (not this PR).
source "${SCRIPT_DIR}/_load-schema-policy.sh"

FAILED=0
while IFS=$'\t' read -r FILE SUBJECT || [ -n "$FILE" ]; do
  case "$FILE" in ''|'#'*) continue ;; esac
  echo "=== ${SUBJECT} (${FILE}) ==="
  if ! bash "${SCRIPT_DIR}/check-schema-compatibility.sh" "${PROTO_DIR}/${FILE}" "${SUBJECT}"; then
    FAILED=1
  fi
  echo
done <<< "$SCHEMA_LIST"

if [ "$FAILED" -ne 0 ]; then
  echo "One or more schema changes break ${COMPATIBILITY_LEVEL} compatibility — see above."
  exit 1
fi
echo "All schemas compatible."
