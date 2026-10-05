{{- define "resonance-flow.chain.dbInitScript" -}}
{{- $component := .Values.components.chain }}
{{- $datasource := $component.config.data.datasource }}
create-chain-database.sh: |
  #!/bin/bash
  (
    set -euo pipefail
    if [ -n "${POSTGRES_PASSWORD_FILE:-}" ]; then
      export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
    else
      export PGPASSWORD="$POSTGRES_PASSWORD"
    fi
    # Mounted from the optional chain-db-credentials Secret (values.yaml's
    # postgresql.primary.extraVolumes). No exit here: Bitnami sources non-executable
    # init scripts, so exit would abort its whole init.
    CHAIN_DB_PASSWORD_FILE=/etc/resonance-flow/secrets/chain-db/password
    if [ -s "$CHAIN_DB_PASSWORD_FILE" ]; then
      # :'chain_pw' makes psql quote it as a literal - never spliced into the SQL text.
      psql -v ON_ERROR_STOP=1 -v chain_pw="$(cat "$CHAIN_DB_PASSWORD_FILE")" --username postgres <<-'EOSQL'
        CREATE USER {{ $datasource.username }} WITH PASSWORD :'chain_pw';
        CREATE DATABASE {{ $datasource.database }} OWNER {{ $datasource.username }};
  EOSQL
    else
      echo "chain-db-credentials not provided - skipping Chain's database" >&2
    fi
  )
{{- end -}}
