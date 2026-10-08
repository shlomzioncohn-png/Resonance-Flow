{{- /*
Creates a random-password Secret via a hook Job. Params: {root, component,
selfHeal, extraScriptTemplate?, extraSelfHealSecretName?}.
*/ -}}
{{- define "resonance-flow.dbCredentialsHook" -}}
{{- $root := .root }}
{{- $component := .component }}
{{- $selfHeal := .selfHeal }}
{{- $secretName := printf "%s-%s-db-credentials" (include "resonance-flow.fullname" $root) $component }}
{{- $name := printf "%s-init" $secretName }}
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
  {{- if $selfHeal }}
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get", "delete"]
    resourceNames:
      - {{ $secretName }}
      {{- if .extraSelfHealSecretName }}
      - {{ .extraSelfHealSecretName }}
      {{- end }}
  {{- end }}
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
        app.kubernetes.io/component: {{ $component }}-db-credentials-init
    spec:
      serviceAccountName: {{ $name }}
      restartPolicy: Never
      securityContext:
        runAsNonRoot: true
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
              PASSWORD="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
              if [ -z "$PASSWORD" ]; then
                echo "Generated an empty password, refusing to continue" >&2
                exit 1
              fi
              create() {
                curl -sS -o /tmp/response -w '%{http_code}' \
                  --cacert "$SA/ca.crt" \
                  -H "Authorization: Bearer $(cat "$SA/token")" \
                  -H "Content-Type: application/json" \
                  -X POST "$API" -d "$1"
              }
              DB_BODY="{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"metadata\":{\"name\":\"{{ $secretName }}\"},\"type\":\"Opaque\",\"stringData\":{\"password\":\"$PASSWORD\"}}"
              CODE=$(create "$DB_BODY")
              case "$CODE" in
                201) echo "Created {{ $secretName }}" ;;
                409)
                  {{- if $selfHeal }}
                  GET_CODE=$(curl -sS -o /tmp/get-response.json -w '%{http_code}' \
                    --cacert "$SA/ca.crt" \
                    -H "Authorization: Bearer $(cat "$SA/token")" \
                    -X GET "$API/{{ $secretName }}?pretty=false")
                  if [ "$GET_CODE" != "200" ]; then
                    echo "Unexpected HTTP $GET_CODE reading {{ $secretName }}:" >&2
                    cat /tmp/get-response.json >&2
                    exit 1
                  fi
                  EXISTING_PASSWORD="$(sed -n 's/.*"password": *"\([^"]*\)".*/\1/p' /tmp/get-response.json | base64 -d)"
                  if [ -n "$EXISTING_PASSWORD" ]; then
                    PASSWORD="$EXISTING_PASSWORD"
                    echo "{{ $secretName }} already exists and looks valid - reusing its password"
                  else
                    echo "{{ $secretName }} exists but its password is empty - recreating it" >&2
                    DELETE_CODE=$(curl -sS -o /tmp/delete-response -w '%{http_code}' \
                      --cacert "$SA/ca.crt" \
                      -H "Authorization: Bearer $(cat "$SA/token")" \
                      -X DELETE "$API/{{ $secretName }}")
                    case "$DELETE_CODE" in
                      200|202) ;;
                      *) echo "Unexpected HTTP $DELETE_CODE deleting the broken {{ $secretName }}:" >&2; cat /tmp/delete-response >&2; exit 1 ;;
                    esac
                    RECREATE_CODE=$(create "$DB_BODY")
                    if [ "$RECREATE_CODE" != "201" ]; then
                      echo "Unexpected HTTP $RECREATE_CODE recreating {{ $secretName }}:" >&2
                      cat /tmp/response >&2
                      exit 1
                    fi
                    echo "Recreated {{ $secretName }} with a new password"
                  fi
                  {{- else }}
                  echo "{{ $secretName }} already exists - left as is"
                  {{- end }}
                  ;;
                *) echo "Unexpected HTTP $CODE creating {{ $secretName }}:" >&2; cat /tmp/response >&2; exit 1 ;;
              esac
              {{- if .extraScriptTemplate }}
              {{ include .extraScriptTemplate $root | nindent 14 }}
              {{- end }}
          resources:
            requests:
              cpu: 10m
              memory: 16Mi
            limits:
              cpu: 100m
              memory: 64Mi
{{- end }}
