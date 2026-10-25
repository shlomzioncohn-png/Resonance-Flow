{{- define "resonance-flow.inbox.dbInitScript" -}}
{{- $datasource := .Values.components.inbox.config.data.datasource }}
create-inbox-database.sh: |
  #!/bin/bash
  (
    set -euo pipefail
    if [ -n "${POSTGRES_PASSWORD_FILE:-}" ]; then
      export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
    else
      export PGPASSWORD="$POSTGRES_PASSWORD"
    fi
    # Mounted from the optional inbox-db-credentials Secret (values.yaml's
    # postgresql.primary.extraVolumes). No exit here: Bitnami sources non-executable
    # init scripts, so exit would abort its whole init.
    INBOX_DB_PASSWORD_FILE=/etc/resonance-flow/secrets/inbox-db/password
    if [ -s "$INBOX_DB_PASSWORD_FILE" ]; then
      # pgcrypto is created here as superuser so V1's CREATE EXTENSION IF NOT EXISTS
      # is a no-op.
      psql -v ON_ERROR_STOP=1 -v inbox_pw="$(cat "$INBOX_DB_PASSWORD_FILE")" --username postgres <<'EOSQL'
        CREATE USER {{ $datasource.username }} WITH PASSWORD :'inbox_pw';
        CREATE DATABASE {{ $datasource.database }} OWNER {{ $datasource.username }};
        \connect {{ $datasource.database }}
        CREATE EXTENSION IF NOT EXISTS pgcrypto;
  EOSQL
    else
      echo "inbox-db-credentials not provided - skipping Inbox's database" >&2
    fi
  )
{{- end -}}
