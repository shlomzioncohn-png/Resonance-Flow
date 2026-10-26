#!/usr/bin/env bash
# Sourced by check-schema-compatibility.sh and register-schema.sh.
#
# Turns each local `import "x.proto";` in SCHEMA_FILE into a registry reference
# to the subject "x.proto" at its latest version — without it the registry stores
# the schema with an unresolvable import. The subject is the file name because
# that's where Confluent serializers look imports up (#618), so x.proto needs a
# "library" row (x.proto<TAB>x.proto) in the tsv, listed before its importers.
# google/protobuf/* imports are built into the registry, so they're skipped.
#
# Requires in the environment: SCHEMA_FILE, REGISTRY_URL
# Optional: SCHEMA_SUBJECTS_FILE (default: schema-subjects.tsv next to this file)
# Sets: REFERENCES (JSON array) and BODY (the registry request: schema + references)

SCHEMA_SUBJECTS_FILE="${SCHEMA_SUBJECTS_FILE:-$(dirname "${BASH_SOURCE[0]}")/schema-subjects.tsv}"

REFERENCES='[]'
while read -r IMPORT; do
  [ -n "$IMPORT" ] || continue
  if ! awk -F'\t' -v f="$IMPORT" '{ sub(/\r$/, "") } $1 == f && $2 == f { found = 1 } END { exit !found }' "$SCHEMA_SUBJECTS_FILE"; then
    echo "'${SCHEMA_FILE}' imports '${IMPORT}', which has no library row (${IMPORT}<TAB>${IMPORT}) in ${SCHEMA_SUBJECTS_FILE}" >&2
    exit 1
  fi
  if ! REF_VERSION="$(curl -sf "${REGISTRY_URL}/subjects/${IMPORT}/versions/latest" | jq -er '.version')"; then
    echo "'${SCHEMA_FILE}' imports '${IMPORT}', but '${IMPORT}' isn't registered yet" >&2
    exit 1
  fi
  REFERENCES="$(jq -c --arg n "$IMPORT" --argjson v "$REF_VERSION" \
    '. + [{name: $n, subject: $n, version: $v}]' <<< "$REFERENCES")"
done < <(sed -n 's/^import "\([^"]*\)";.*/\1/p' "$SCHEMA_FILE" | grep -v '^google/protobuf/' || true)

BODY=$(jq -n --arg schema "$(cat "$SCHEMA_FILE")" --argjson refs "$REFERENCES" \
  '{schema: $schema, schemaType: "PROTOBUF", references: $refs}')
