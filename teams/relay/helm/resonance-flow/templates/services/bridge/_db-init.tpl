{{- define "resonance-flow.bridge.dbInitScript" -}}
{{- $postgres := .Values.components.bridge.config.bridge.postgres }}
create-bridge-database.sh: |
  #!/bin/bash
  (
    set -euo pipefail
    if [ -n "${POSTGRES_PASSWORD_FILE:-}" ]; then
      export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
    else
      export PGPASSWORD="$POSTGRES_PASSWORD"
    fi
    # Optional in PostgreSQL's pod; the pre-install/pre-upgrade hook creates it.
    # No exit here: Bitnami sources init scripts, so that would stop its whole init.
    BRIDGE_DB_PASSWORD_FILE=/etc/resonance-flow/secrets/bridge-db/password
    if [ -s "$BRIDGE_DB_PASSWORD_FILE" ]; then
      # psql quotes the password as a literal; the chart quotes SQL identifiers.
      psql -v ON_ERROR_STOP=1 -v bridge_pw="$(cat "$BRIDGE_DB_PASSWORD_FILE")" --username postgres <<'EOSQL'
        CREATE USER "{{ $postgres.user | replace "\"" "\"\"" }}" WITH PASSWORD :'bridge_pw';
        CREATE DATABASE "{{ $postgres.database | replace "\"" "\"\"" }}" OWNER "{{ $postgres.user | replace "\"" "\"\"" }}";
  EOSQL
    else
      echo "bridge-db-credentials not provided - skipping Bridge's database" >&2
    fi
  )
{{- end -}}
