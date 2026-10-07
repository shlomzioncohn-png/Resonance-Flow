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
Name for the pipe-consumer-group-sweep CronJob and its ConfigMap. CronJob
names are capped at 52 chars, not the usual 63 (a Job name it spawns gets an
11-char timestamp suffix appended, and Job names are capped at 63) - trunc 63
via resonance-flow.fullname isn't enough on its own, e.g. lens-gateway and
lens-frontend overflow 52 while lens-bff, being shorter, happens not to.
*/}}
{{- define "resonance-flow.pipeConsumerGroupSweepFullname" -}}
{{- printf "%s-pipe-consumer-group-sweep" (include "resonance-flow.fullname" .) | trunc 52 | trimSuffix "-" }}
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
{{/*
Resolve a logical dependency name to the concrete provider name.
*/}}
{{- define "resonance-flow.dependencyProviderName" -}}
{{- if eq .name "object-storage" }}
{{- .root.Values.objectStorage.provider }}
{{- else }}
{{- .name }}
{{- end }}
{{- end }}
{{- define "resonance-flow.dependencyHost" -}}
{{- $dependencyName := include "resonance-flow.dependencyProviderName" (dict "root" .root "name" .dep.name) }}
{{- if .dep.host }}
{{- .dep.host }}
{{- else if hasKey .root.Values.components $dependencyName }}
{{- printf "%s-%s" (include "resonance-flow.fullname" .root) $dependencyName }}
{{- else }}
{{- printf "%s-%s" .root.Release.Name $dependencyName }}
{{- end }}
{{- end }}
{{/*
Resolved host:port of a named entry in a {name, port, host?} list
(dependencies' shape) - so a component's own connection string doesn't
re-derive it separately from the wait-for-<dep> initContainer.
*/}}
{{- define "resonance-flow.dependencyAddress" -}}
{{- $addr := "" }}
{{- $root := .root }}
{{- range .deps }}
{{- if eq .name $.name }}
{{- $addr = printf "%s:%v" (include "resonance-flow.dependencyHost" (dict "root" $root "dep" .)) .port }}
{{- end }}
{{- end }}
{{- $addr }}
{{- end }}

{{/*
One initContainer per {name, port, host?} dependency, blocking until host:port is
TCP-reachable. Dependencies whose target is disabled get no initContainer.
*/}}
{{- define "resonance-flow.waitForDependencies" -}}
{{- $root := .root }}
{{- range .deps }}
{{- $dep := . }}
{{- $dependencyName := include "resonance-flow.dependencyProviderName" (dict "root" $root "name" $dep.name) }}
{{- $enabled := true }}
{{- if not $dep.host }}
{{- if hasKey $root.Values.components $dependencyName }}
{{- if eq (index $root.Values.components $dependencyName).enabled false }}
{{- $enabled = false }}
{{- end }}
{{- else if hasKey $root.Values $dependencyName }}
{{- if eq (index $root.Values $dependencyName).enabled false }}
{{- $enabled = false }}
{{- end }}
{{- end }}
{{- end }}
{{- if $enabled }}
{{- $host := include "resonance-flow.dependencyHost" (dict "root" $root "dep" $dep) }}
- name: wait-for-{{ $dep.name }}
  image: busybox:1.36
  command:
    - sh
    - -c
    - |
      until nc -z -w2 {{ $host }} {{ $dep.port }}; do
        echo "waiting for {{ $dep.name }} ({{ $host }}:{{ $dep.port }})..."
        sleep 2
      done
{{- end }}
{{- end }}
{{- end }}
{{/*
Kafka SASL client JAAS config string, derived from kafka.sasl.client.users
and kafka.sasl.client.passwords - the same values that seed the Kafka subchart's own
auto-generated Secret (see charts/kafka/templates/secrets.yaml). Every component that
talks to Kafka should render its credentials through this instead of duplicating the
username/password as its own constant (the same kind of copy once let inbox's Postgres
password drift from postgresql.auth.postgresPassword). Takes the root context ($),
not a scoped one, since kafka.sasl.client.* lives at the top level of Values.
*/}}
{{- define "resonance-flow.kafkaSaslJaasConfig" -}}
{{- printf "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";" (first .Values.kafka.sasl.client.users) (first .Values.kafka.sasl.client.passwords) }}
{{- end }}

{{/*
Standard ConfigMap header (apiVersion/kind/metadata) for a component's mounted
config.yaml, named "<fullname>-<component>-config" - identical shape shared by
every component that mounts one. Takes {root, component}.
*/}}
{{- define "resonance-flow.configMapHeader" -}}
apiVersion: v1
kind: ConfigMap

metadata:
  name: {{ include "resonance-flow.fullname" .root }}-{{ .component }}-config
  labels:
    {{- include "resonance-flow.labels" .root | nindent 4 }}
    app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/*
Kafka bootstrap-servers + SASL_PLAINTEXT security/properties block, identical
across every component's mounted config.yaml - only extra client properties
(e.g. inbox's schema.registry.url) and the producer serializer vary per
component and stay in the caller. Takes {root}.
*/}}
{{- define "resonance-flow.springKafkaBase" -}}
bootstrap-servers: {{ include "resonance-flow.dependencyAddress" (dict "root" .root "deps" .root.Values.dependencies "name" "kafka") | quote }}
security:
  protocol: SASL_PLAINTEXT
properties:
  sasl.mechanism: "PLAIN"
  sasl.jaas.config: {{ include "resonance-flow.kafkaSaslJaasConfig" .root | quote }}
{{- end }}

{{/*
Kafka consumer defaults (offset reset, auto-commit) - only the consumer group
id varies. No deserializers: the app owns its wire format. Takes {groupId}.
*/}}
{{- define "resonance-flow.springKafkaConsumerDefaults" -}}
consumer:
  group-id: {{ .groupId | quote }}
  auto-offset-reset: earliest
  enable-auto-commit: false
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
