{{- define "resonance-flow.mind.dbInitScript" -}}
{{- $mindVectorStore := .Values.components.mind.config.vector_store }}
create-mind-database.sh: |
  #!/bin/bash
  set -euo pipefail
  if [ -n "${POSTGRES_PASSWORD_FILE:-}" ]; then
    export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  else
    export PGPASSWORD="$POSTGRES_PASSWORD"
  fi
  psql -v ON_ERROR_STOP=1 -v mind_pw={{ .Values.components.mind.secret.data.vector_store_password | quote }} --username postgres <<-'EOSQL'
      CREATE USER {{ $mindVectorStore.user }} WITH PASSWORD :'mind_pw';
      CREATE DATABASE {{ $mindVectorStore.db }} OWNER {{ $mindVectorStore.user }};
      \connect {{ $mindVectorStore.db }}
      CREATE EXTENSION IF NOT EXISTS vector;
  EOSQL
{{- end -}}
