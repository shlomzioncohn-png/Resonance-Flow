{{- define "resonance-flow.mind.dbInitScript" -}}
{{- $mindVectorStore := .Values.components.mind.config.vector_store }}
create-mind-database.sh: |
  #!/bin/bash
  (
    set -euo pipefail
    if [ -n "${POSTGRES_PASSWORD_FILE:-}" ]; then
      export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
    else
      export PGPASSWORD="$POSTGRES_PASSWORD"
    fi
    MIND_DB_PASSWORD_FILE=/etc/resonance-flow/secrets/mind-db/password
    if [ -s "$MIND_DB_PASSWORD_FILE" ]; then
      psql -v ON_ERROR_STOP=1 -v mind_pw="$(cat "$MIND_DB_PASSWORD_FILE")" --username postgres <<-'EOSQL'
        CREATE USER {{ $mindVectorStore.user }} WITH PASSWORD :'mind_pw';
        CREATE DATABASE {{ $mindVectorStore.db }} OWNER {{ $mindVectorStore.user }};
        \connect {{ $mindVectorStore.db }}
        CREATE EXTENSION IF NOT EXISTS vector;
  EOSQL
    else
      echo "mind-db-credentials not provided - skipping MIND's database" >&2
    fi
  )
{{- end -}}
