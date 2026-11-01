{{/*
Per-hook ServiceAccount/Role/RoleBinding for a Secret-creating hook Job.
Takes {root, name, secretName, component}; name is used as
"<fullname>-<name>-init" for all three objects - the caller can reuse it
as its Job's serviceAccountName.
*/}}
{{- define "resonance-flow.lens.secretHookRbac" -}}
{{- $root := .root }}
{{- $rbacName := printf "%s-%s-init" (include "resonance-flow.fullname" $root) .name }}
---
apiVersion: v1
kind: ServiceAccount

metadata:
  name: {{ $rbacName }}
  labels:
    {{- include "resonance-flow.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ .component }}
  annotations:
    helm.sh/hook: pre-install,pre-upgrade
    helm.sh/hook-weight: "-10"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role

metadata:
  name: {{ $rbacName }}
  labels:
    {{- include "resonance-flow.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ .component }}
  annotations:
    helm.sh/hook: pre-install,pre-upgrade
    helm.sh/hook-weight: "-10"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded

rules:
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["create"]
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get", "delete"]
    resourceNames:
      - {{ .secretName }}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding

metadata:
  name: {{ $rbacName }}
  labels:
    {{- include "resonance-flow.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ .component }}
  annotations:
    helm.sh/hook: pre-install,pre-upgrade
    helm.sh/hook-weight: "-10"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded

roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: {{ $rbacName }}
subjects:
  - kind: ServiceAccount
    name: {{ $rbacName }}
    namespace: {{ $root.Release.Namespace }}
{{- end }}
