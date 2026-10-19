{{- define "resonance-flow.bridge.dbInitScript" -}}
{{- include "resonance-flow.dbInitScript" (dict
  "root" .
  "component" "bridge"
  "scriptName" "create-bridge-database.sh"
  "sqlTemplate" "resonance-flow.bridge.dbInitSql"
) }}
{{- end -}}

{{- define "resonance-flow.bridge.dbInitSql" -}}
{{- $postgres := .Values.components.bridge.config.bridge.postgres -}}
CREATE USER "{{ $postgres.user | replace "\"" "\"\"" }}" WITH PASSWORD :'bridge_pw';
CREATE DATABASE "{{ $postgres.database | replace "\"" "\"\"" }}" OWNER "{{ $postgres.user | replace "\"" "\"\"" }}";
{{- end -}}