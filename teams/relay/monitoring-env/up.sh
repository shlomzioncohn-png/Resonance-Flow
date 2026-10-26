#!/usr/bin/env bash
# Grafana + Prometheus on a local kind cluster, with the resonance-flow dashboards -
# see README.md. Safe to re-run: every step is an upgrade.
set -euo pipefail

CLUSTER="${CLUSTER:-rf-monitoring}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
KPS_APP="$REPO/teams/relay/argocd/apps/kube-prometheus-stack.yaml"
CHART="$REPO/teams/relay/helm/resonance-flow"
SHARED="$CHART/environments/shared.yaml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for tool in docker kind kubectl helm yq; do
  command -v "$tool" >/dev/null || { echo "Missing $tool on PATH" >&2; exit 1; }
done

echo "==> 1/4 kind cluster $CLUSTER"
kind get clusters 2>/dev/null | tr -d '\r' | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"
k() { kubectl --context "kind-$CLUSTER" "$@"; }
h() { helm --kube-context "kind-$CLUSTER" "$@"; }

echo "==> 2/4 Grafana's admin login: admin / admin"
# On the shared cluster a hook creates it with a random password; here a fixed one is easier.
k create namespace monitoring --dry-run=client -o yaml | k apply -f -
k -n monitoring create secret generic grafana-admin \
  --from-literal=admin-user=admin --from-literal=admin-password=admin \
  --dry-run=client -o yaml | k apply -f -

echo "==> 3/4 kube-prometheus-stack (Prometheus + Grafana), same values as ArgoCD"
yq '.spec.sources[0].helm.valuesObject' "$KPS_APP" > "$WORK/kps.yaml"
# tr: yq's Windows build may end lines with CRLF.
h upgrade --install monitoring kube-prometheus-stack \
  --repo "$(yq '.spec.sources[0].repoURL' "$KPS_APP" | tr -d '\r')" \
  --version "$(yq '.spec.sources[0].targetRevision' "$KPS_APP" | tr -d '\r')" \
  --namespace monitoring \
  -f "$SHARED" -f "$WORK/kps.yaml"

echo "==> 4/4 resonance-flow: dashboards + Kafka/PostgreSQL/MinIO (every service off)"
helm dependency build "$CHART" >/dev/null
yq '{"components": (.components | map_values({"enabled": false}))}' "$CHART/values.yaml" > "$WORK/components-off.yaml"
h upgrade --install resonance-flow "$CHART" \
  --namespace resonance-flow --create-namespace \
  -f "$CHART/values.yaml" -f "$WORK/components-off.yaml" -f "$SHARED"

# Not helm --wait: on a slow first pull a Deployment's progress deadline fails the
# install, though the pod comes up a few minutes later. Only Grafana is waited for -
# Kafka/PostgreSQL/MinIO keep starting in the background and their panels fill in.
echo "==> Waiting for Grafana (the first run downloads every image - can take a while)"
k -n monitoring wait --for=condition=Available deployment/monitoring-grafana --timeout=60m

echo
echo "Grafana: http://localhost:3000  (admin / admin) - Ctrl+C to stop."
echo "Later, without re-running this: kubectl --context kind-$CLUSTER -n monitoring port-forward svc/monitoring-grafana 3000:80"
k -n monitoring port-forward svc/monitoring-grafana 3000:80
