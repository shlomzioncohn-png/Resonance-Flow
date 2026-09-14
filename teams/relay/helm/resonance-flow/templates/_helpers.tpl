{{/*
Expand the name of the chart.
*/}}
{{- define "resonance-flow.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "resonance-flow.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "resonance-flow.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "resonance-flow.labels" -}}
helm.sh/chart: {{ include "resonance-flow.chart" . }}
{{ include "resonance-flow.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "resonance-flow.selectorLabels" -}}
app.kubernetes.io/name: {{ include "resonance-flow.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "resonance-flow.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "resonance-flow.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Build and base64-encode a .dockerconfigjson value from imagePullSecret.* values
*/}}
{{- define "resonance-flow.dockerconfigjson" -}}
{{- $auth := printf "%s:%s" .Values.imagePullSecret.username .Values.imagePullSecret.password | b64enc }}
{{- $config := dict "auths" (dict .Values.imagePullSecret.registry (dict "username" .Values.imagePullSecret.username "password" .Values.imagePullSecret.password "email" .Values.imagePullSecret.email "auth" $auth)) }}
{{- $config | toJson | b64enc }}
{{- end }}

{{/*
Resolve one dependency entry's host: an explicit `host` wins (an external
Postgres/Kafka/whatever, not one of our own subcharts); else, if the name
matches one of our own components.*, it's that component's own Service,
<fullname>-<name> (same pattern service.yaml uses) - not the Bitnami
subchart pattern below, which would silently 404/DNS-fail (verified live:
wait-for-schema-registry hung forever against "inbox-schema-registry",
which is nothing - the real Service is "inbox-resonance-flow-schema-registry").
Otherwise it's a Bitnami subchart's Service, <release-name>-<name>.
*/}}
{{- define "resonance-flow.dependencyHost" -}}
{{- if .dep.host }}
{{- .dep.host }}
{{- else if hasKey .root.Values.components .dep.name }}
{{- printf "%s-%s" (include "resonance-flow.fullname" .root) .dep.name }}
{{- else }}
{{- printf "%s-%s" .root.Release.Name .dep.name }}
{{- end }}
{{- end }}

{{/*
Look up a named entry's resolved host in a {name, port, host?} list
(dependencies' shape) - so a component's own connection string doesn't
re-derive it separately from the wait-for-<dep> initContainer.
*/}}
{{- define "resonance-flow.dependencyHostByName" -}}
{{- $host := "" }}
{{- $root := .root }}
{{- range .deps }}
{{- if eq .name $.name }}
{{- $host = include "resonance-flow.dependencyHost" (dict "root" $root "dep" .) }}
{{- end }}
{{- end }}
{{- $host }}
{{- end }}

{{/*
Look up a named entry's port in a {name, port, host?} list (dependencies'
shape), so it isn't duplicated as a literal outside its wait-for-<dep>
initContainer.
*/}}
{{- define "resonance-flow.dependencyPort" -}}
{{- $port := "" }}
{{- range .deps }}
{{- if eq .name $.name }}
{{- $port = .port }}
{{- end }}
{{- end }}
{{- $port }}
{{- end }}

{{/*
One initContainer per {name, port, host?} dependency, blocking until
host:port is TCP-reachable.
*/}}
{{- define "resonance-flow.waitForDependencies" -}}
{{- $root := .root }}
{{- range .deps }}
{{- $host := include "resonance-flow.dependencyHost" (dict "root" $root "dep" .) }}
- name: wait-for-{{ .name }}
  image: busybox:1.36
  command:
    - sh
    - -c
    - |
      until nc -z -w2 {{ $host }} {{ .port }}; do
        echo "waiting for {{ .name }} ({{ $host }}:{{ .port }})..."
        sleep 2
      done
{{- end }}
{{- end }}

{{/*
Kafka SASL client username / JAAS config string, derived from kafka.sasl.client.users
and kafka.sasl.client.passwords - the same values that seed the Kafka subchart's own
auto-generated Secret (see charts/kafka/templates/secrets.yaml). Every component that
talks to Kafka should render its credentials through these instead of duplicating the
username/password as its own constant - that duplication is exactly what caused the
Postgres password to drift out of sync (components.inbox.config.data.datasource.password
vs postgresql.auth.postgresPassword) before it was caught. Takes the root context ($),
not a scoped one, since kafka.sasl.client.* lives at the top level of Values.
*/}}
{{- define "resonance-flow.kafkaSaslUsername" -}}
{{- first .Values.kafka.sasl.client.users }}
{{- end }}

{{- define "resonance-flow.kafkaSaslJaasConfig" -}}
{{- printf "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";" (first .Values.kafka.sasl.client.users) (first .Values.kafka.sasl.client.passwords) }}
{{- end }}

{{/*
Render the env entries for a component whose config is mounted as a file
at /etc/resonance-flow/config/config.yaml. SPRING_CONFIG_ADDITIONAL_LOCATION
is what actually makes Spring Boot load the mounted file (processed after
the classpath application.yml, so its values win); CONFIG_FILE_PATH is kept
for anything that wants the raw path.
*/}}
{{- define "resonance-flow.configFilePathEnv" -}}
- name: CONFIG_FILE_PATH
  value: "/etc/resonance-flow/config/config.yaml"
- name: SPRING_CONFIG_ADDITIONAL_LOCATION
  value: "file:/etc/resonance-flow/config/config.yaml"
{{- end }}
