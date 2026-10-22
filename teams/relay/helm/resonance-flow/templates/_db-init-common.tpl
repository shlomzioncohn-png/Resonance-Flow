{{- define "resonance-flow.dbInitScript" -}}
{{ .scriptName }}: |
  #!/bin/bash
  (
    set -euo pipefail
    if [ -n "${POSTGRES_PASSWORD_FILE:-}" ]; then
      export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
    else
      export PGPASSWORD="$POSTGRES_PASSWORD"
    fi
    DB_PASSWORD_FILE=/etc/resonance-flow/secrets/{{ .component }}-db/password
    if [ -s "$DB_PASSWORD_FILE" ]; then
      psql -v ON_ERROR_STOP=1 -v {{ .component }}_pw="$(cat "$DB_PASSWORD_FILE")" --username postgres <<'EOSQL'
{{ include .sqlTemplate .root | indent 8 }}
  EOSQL
    else
      echo "{{ .component }}-db-credentials not provided - skipping {{ .component | title }}'s database" >&2
    fi
  )
{{- end -}}