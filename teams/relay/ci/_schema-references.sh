#!/usr/bin/env bash
# Sourced by check-schema-compatibility.sh and register-schema.sh.
#
# Turns each local `import "x.proto";` in SCHEMA_FILE into a registry reference
# to x.proto's subject (looked up in the subjects tsv) at its latest version —
# without it the registry stores the schema with an unresolvable import.
# google/protobuf/* imports are built into the registry, so they're skipped.
# A referenced subject must already be registered: list it earlier in the tsv.
#
# Requires in the environment: SCHEMA_FILE, REGISTRY_URL
# Optional: SCHEMA_SUBJECTS_FILE (default: schema-subjects.tsv next to this file)
# Sets: REFERENCES (JSON array)

SCHEMA_SUBJECTS_FILE="${SCHEMA_SUBJECTS_FILE:-$(dirname "${BASH_SOURCE[0]}")/schema-subjects.tsv}"

REFERENCES='[]'
while read -r IMPORT; do
  [ -n "$IMPORT" ] || continue
  REF_SUBJECT="$(awk -F'\t' -v f="$IMPORT" '$1 == f { print $2; exit }' "$SCHEMA_SUBJECTS_FILE")"
  if [ -z "$REF_SUBJECT" ]; then
    echo "'${SCHEMA_FILE}' imports '${IMPORT}', which has no subject in ${SCHEMA_SUBJECTS_FILE}" >&2
    exit 1
  fi
  if ! REF_VERSION="$(curl -sf "${REGISTRY_URL}/subjects/${REF_SUBJECT}/versions/latest" | jq -er '.version')"; then
    echo "'${SCHEMA_FILE}' imports '${IMPORT}', but '${REF_SUBJECT}' isn't registered yet" >&2
    exit 1
  fi
  REFERENCES="$(jq -c --arg n "$IMPORT" --arg s "$REF_SUBJECT" --argjson v "$REF_VERSION" \
    '. + [{name: $n, subject: $s, version: $v}]' <<< "$REFERENCES")"
done < <(sed -n 's/^import "\([^"]*\)";.*/\1/p' "$SCHEMA_FILE" | grep -v '^google/protobuf/' || true)
