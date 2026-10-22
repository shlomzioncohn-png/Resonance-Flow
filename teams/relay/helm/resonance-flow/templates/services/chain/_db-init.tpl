{{- define "resonance-flow.chain.dbInitScript" -}}
{{- include "resonance-flow.dbInitScript" (dict
  "root" .
  "component" "chain"
  "scriptName" "create-chain-database.sh"
  "sqlTemplate" "resonance-flow.chain.dbInitSql"
) }}
{{- end -}}

{{- define "resonance-flow.chain.dbInitSql" -}}
{{- $datasource := .Values.components.chain.config.data.datasource -}}
CREATE USER {{ $datasource.username }} WITH PASSWORD :'chain_pw';
CREATE DATABASE {{ $datasource.database }} OWNER {{ $datasource.username }};
{{- end -}}