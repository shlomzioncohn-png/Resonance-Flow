#!/usr/bin/env bash
# Sourced by seed-registry.sh and check-all-schemas.sh.
#
# Loads the schema list and the compatibility level from BASE_REF (the branch the
# PR targets), NOT from the working tree — so a PR cannot weaken its own gate by
# lowering the level or dropping a schema from the list in the same PR.
#
# Requires in the environment: BASE_REF, REPO_ROOT, SCRIPT_DIR
# Sets: SCHEMA_LIST (the tsv contents), COMPATIBILITY_LEVEL, RESET_SUBJECTS

: "${BASE_REF:?_load-schema-policy.sh: BASE_REF not set}"

_list_path="teams/relay/ci/schema-subjects.tsv"
if git -C "$REPO_ROOT" cat-file -e "${BASE_REF}:${_list_path}" 2>/dev/null; then
  SCHEMA_LIST="$(git -C "$REPO_ROOT" show "${BASE_REF}:${_list_path}")"
else
  echo "note: ${_list_path} not on ${BASE_REF} (base predates the schema CI) — falling back to the working-tree copy"
  SCHEMA_LIST="$(cat "${SCRIPT_DIR}/schema-subjects.tsv")"
fi

# "# compatibility: <LEVEL>" directive line in the tsv. Default FULL.
COMPATIBILITY_LEVEL="$(printf '%s\n' "$SCHEMA_LIST" \
  | awk -F: '/^#[[:space:]]*compatibility:/ {gsub(/[[:space:]]/,"",$2); print $2; exit}')"
COMPATIBILITY_LEVEL="${COMPATIBILITY_LEVEL:-FULL}"

# "# reset: <subject>" lines: subjects whose old versions are dropped, not checked against.
RESET_SUBJECTS="$(printf '%s\n' "$SCHEMA_LIST" \
  | awk -F: '/^#[[:space:]]*reset:/ {gsub(/[[:space:]]/,"",$2); print $2}')"
