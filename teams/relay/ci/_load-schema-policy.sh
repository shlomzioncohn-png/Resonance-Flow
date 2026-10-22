#!/usr/bin/env bash
# Sourced by seed-registry.sh, check-all-schemas.sh and register-added-schemas.sh.
#
# Loads the schema list and the compatibility level from BASE_REF (the branch the
# PR targets), NOT from the working tree — so a PR cannot weaken its own gate by
# lowering the level or dropping a schema from the list in the same PR.
#
# Requires in the environment: BASE_REF, REPO_ROOT, SCRIPT_DIR
# Sets: SCHEMA_LIST (the tsv contents), ADDED_ROWS, COMPATIBILITY_LEVEL, RESET_SUBJECTS

: "${BASE_REF:?_load-schema-policy.sh: BASE_REF not set}"

_list_path="teams/relay/ci/schema-subjects.tsv"
if git -C "$REPO_ROOT" cat-file -e "${BASE_REF}:${_list_path}" 2>/dev/null; then
  SCHEMA_LIST="$(git -C "$REPO_ROOT" show "${BASE_REF}:${_list_path}")"
else
  echo "note: ${_list_path} not on ${BASE_REF} (base predates the schema CI) — falling back to the working-tree copy"
  SCHEMA_LIST="$(cat "${SCRIPT_DIR}/schema-subjects.tsv")"
fi

# Schema rows this checkout adds (directives excluded): additions only, so they can't weaken
# the gate. register-added-schemas.sh registers them (#857).
ADDED_ROWS="$(awk -F'\t' '{ sub(/\r$/, "") } NR == FNR { seen[$0] = 1; next }
  $1 != "" && $1 !~ /^#/ && !seen[$0]' \
  <(printf '%s\n' "$SCHEMA_LIST") "${SCRIPT_DIR}/schema-subjects.tsv")"

# Added library rows (file == subject) go first: _schema-references.sh can't resolve an
# import without one, even when seeding the base.
_new_libraries="$(printf '%s\n' "$ADDED_ROWS" | awk -F'\t' '$1 != "" && $1 == $2')"
if [ -n "$_new_libraries" ]; then
  SCHEMA_LIST="$(printf '%s\n%s' "$_new_libraries" "$SCHEMA_LIST")"
fi

# "# compatibility: <LEVEL>" directive line in the tsv. Default FULL.
COMPATIBILITY_LEVEL="$(printf '%s\n' "$SCHEMA_LIST" \
  | awk -F: '/^#[[:space:]]*compatibility:/ {gsub(/[[:space:]]/,"",$2); print $2; exit}')"
COMPATIBILITY_LEVEL="${COMPATIBILITY_LEVEL:-FULL}"

# "# reset: <subject>" lines: subjects whose old versions are dropped, not checked against.
RESET_SUBJECTS="$(printf '%s\n' "$SCHEMA_LIST" \
  | awk -F: '/^#[[:space:]]*reset:/ {gsub(/[[:space:]]/,"",$2); print $2}')"
