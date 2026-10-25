{{/*
Creates a random-password Secret. Takes {root, component, secretName,
secretKeys (default ["password"]), enabled}. Each key gets its own password.
*/}}
{{- define "resonance-flow.credentialsHook" -}}
{{- $root := .root }}
{{- $component := .component }}
{{- $secretKeys := .secretKeys | default (list "password") }}
{{- if .enabled }}
{{- $name := printf "%s-init" .secretName }}
{{- $secretName := .secretName }}
---
apiVersion: v1
kind: ServiceAccount

metadata:
  name: {{ $name }}
  labels:
    {{- include "resonance-flow.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ $component }}
  annotations:
    helm.sh/hook: pre-install,pre-upgrade
    helm.sh/hook-weight: "-10"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role

metadata:
  name: {{ $name }}
  labels:
    {{- include "resonance-flow.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ $component }}
  annotations:
    helm.sh/hook: pre-install,pre-upgrade
    helm.sh/hook-weight: "-10"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded

rules:
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["create"]
  # get/patch only on this Secret - to reuse its passwords or fill in a missing one.
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get", "patch"]
    resourceNames:
      - {{ $secretName }}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding

metadata:
  name: {{ $name }}
  labels:
    {{- include "resonance-flow.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ $component }}
  annotations:
    helm.sh/hook: pre-install,pre-upgrade
    helm.sh/hook-weight: "-10"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded

roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: {{ $name }}
subjects:
  - kind: ServiceAccount
    name: {{ $name }}
    namespace: {{ $root.Release.Namespace }}
---
apiVersion: batch/v1
kind: Job

metadata:
  name: {{ $name }}
  labels:
    {{- include "resonance-flow.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ $component }}
  annotations:
    helm.sh/hook: pre-install,pre-upgrade
    helm.sh/hook-weight: "0"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded

spec:
  backoffLimit: 3
  ttlSecondsAfterFinished: 600
  template:
    metadata:
      labels:
        {{- include "resonance-flow.selectorLabels" $root | nindent 8 }}
        # Not the component itself: that would match its Service/Deployment selectors.
        app.kubernetes.io/component: {{ $component }}-credentials-init
    spec:
      serviceAccountName: {{ $name }}
      restartPolicy: Never
      securityContext:
        runAsNonRoot: true
        # The image's curl_user, numeric - runAsNonRoot can't verify a named user.
        runAsUser: 100
        runAsGroup: 101
      containers:
        - name: create-secret
          image: curlimages/curl:8.11.1
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
          command:
            - sh
            - -c
            - |
              set -eu
              SA=/var/run/secrets/kubernetes.io/serviceaccount
              API="https://kubernetes.default.svc/api/v1/namespaces/{{ $root.Release.Namespace }}/secrets"
              DATA=""
              {{- range $i, $key := $secretKeys }}
              PASSWORD="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
              if [ -z "$PASSWORD" ]; then
                echo "Generated an empty password, refusing to continue" >&2
                exit 1
              fi
              DATA="${DATA}{{ if $i }},{{ end }}\"{{ $key }}\":\"$PASSWORD\""
              {{- end }}
              create() {
                curl -sS -o /tmp/response -w '%{http_code}' \
                  --cacert "$SA/ca.crt" \
                  -H "Authorization: Bearer $(cat "$SA/token")" \
                  -H "Content-Type: application/json" \
                  -X POST "$API" -d "$1"
              }
              DB_BODY="{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"metadata\":{\"name\":\"{{ $secretName }}\"},\"type\":\"Opaque\",\"stringData\":{$DATA}}"
              CODE=$(create "$DB_BODY")
              case "$CODE" in
                201) echo "Created {{ $secretName }}" ;;
                409)
                  GET_CODE=$(curl -sS -o /tmp/get-response.json -w '%{http_code}' \
                    --cacert "$SA/ca.crt" \
                    -H "Authorization: Bearer $(cat "$SA/token")" \
                    -X GET "$API/{{ $secretName }}?pretty=false")
                  if [ "$GET_CODE" != "200" ]; then
                    echo "Unexpected HTTP $GET_CODE reading {{ $secretName }}:" >&2
                    cat /tmp/get-response.json >&2
                    exit 1
                  fi
                  MISSING=""
                  {{- range $secretKeys }}
                  if [ -z "$(sed -n 's/.*"{{ . }}": *"\([^"]*\)".*/\1/p' /tmp/get-response.json | base64 -d)" ]; then
                    PASSWORD="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
                    if [ -z "$PASSWORD" ]; then
                      echo "Generated an empty password, refusing to continue" >&2
                      exit 1
                    fi
                    MISSING="${MISSING:+$MISSING,}\"{{ . }}\":\"$PASSWORD\""
                  fi
                  {{- end }}
                  if [ -z "$MISSING" ]; then
                    echo "{{ $secretName }} already exists and looks valid - reusing its passwords"
                  else
                    echo "{{ $secretName }} has a missing or empty password - filling in only those keys" >&2
                    PATCH_CODE=$(curl -sS -o /tmp/patch-response -w '%{http_code}' \
                      --cacert "$SA/ca.crt" \
                      -H "Authorization: Bearer $(cat "$SA/token")" \
                      -H "Content-Type: application/merge-patch+json" \
                      -X PATCH "$API/{{ $secretName }}" -d "{\"stringData\":{$MISSING}}")
                    if [ "$PATCH_CODE" != "200" ]; then
                      echo "Unexpected HTTP $PATCH_CODE patching {{ $secretName }}:" >&2
                      cat /tmp/patch-response >&2
                      exit 1
                    fi
                    echo "Filled in the missing passwords of {{ $secretName }}"
                  fi
                  ;;
                *) echo "Unexpected HTTP $CODE creating {{ $secretName }}:" >&2; cat /tmp/response >&2; exit 1 ;;
              esac
          resources:
            requests:
              cpu: 10m
              memory: 16Mi
            limits:
              cpu: 100m
              memory: 64Mi
{{- end }}
{{- end }}

{{/*
A component's DB password Secret. Takes {root, component}.
*/}}
{{- define "resonance-flow.dbCredentialsHook" -}}
{{- $secretName := printf "%s-%s-db-credentials" (include "resonance-flow.fullname" .root) .component }}
{{- include "resonance-flow.credentialsHook" (dict "root" .root "component" .component "secretName" $secretName "enabled" .root.Values.postgresql.enabled) }}
{{- end }}
