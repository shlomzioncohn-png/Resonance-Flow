#!/usr/bin/env bash
# Proves the LENS<->Inbox round trip live (#411): deploys lens-bff + Inbox
# (+ their dependencies) through the real Helm chart with real images and
# mocking off, creates one real task through Inbox's own API, then confirms
# lens-bff fetches it back through its real InboxClient - not the mock.
#
# Usage: ./round-trip.sh [--clean]
#   --clean   delete the kind cluster at the end (default: leave it running)
#
# Requires: docker, kind, helm, kubectl, yq - all already on this machine.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

CLUSTER_NAME="lens-round-trip-cluster"
NAMESPACE="lens-round-trip"
CHART_DIR="teams/relay/helm/resonance-flow"
CHART_VALUES="${CHART_DIR}/values.yaml"
CHART_ALL_OFF="${CHART_DIR}/.round-trip-all-off.yaml"
CLEAN=0

for arg in "$@"; do
  [ "$arg" = "--clean" ] && CLEAN=1
done

for bin in docker kind helm kubectl yq; do
  command -v "$bin" >/dev/null 2>&1 || { echo "FAIL: '$bin' is required but not installed"; exit 1; }
done

cleanup() {
  rm -f "$CHART_ALL_OFF"
  if [ "$CLEAN" -eq 1 ]; then
    echo "==> --clean: deleting kind cluster $CLUSTER_NAME"
    kind delete cluster --name "$CLUSTER_NAME" || true
  else
    echo "==> Leaving kind cluster $CLUSTER_NAME running (pass --clean to tear it down)"
  fi
}
trap cleanup EXIT

echo "==> Building local images (lens-bff, inbox)"
docker build -t lens-bff:local teams/lens/bff
docker build -t inbox:local teams/inbox --build-context shared=shared

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  echo "==> kind cluster $CLUSTER_NAME already exists, reusing it"
else
  echo "==> Creating kind cluster $CLUSTER_NAME"
  kind create cluster --name "$CLUSTER_NAME"
fi

NODE="${CLUSTER_NAME}-control-plane"
NETFREE_CA="/c/ProgramData/NetFree/CA/netfree-ca-bundle-curl.crt"
if [ -f "$NETFREE_CA" ]; then
  echo "==> Trusting NetFree's proxy CA inside the kind node (needed to pull kafka/postgres/schema-registry images)"
  docker cp "$NETFREE_CA" "${NODE}:/usr/local/share/ca-certificates/netfree.crt"
  docker exec "$NODE" update-ca-certificates
  docker exec "$NODE" systemctl restart containerd
  sleep 3
fi

for img in lens-bff inbox; do
  echo "==> Loading $img into kind"
  kind load docker-image "${img}:local" --name "$CLUSTER_NAME"
done

echo "==> Fetching chart dependencies"
helm dependency build "$CHART_DIR"

echo "==> Building all-components-off overlay"
yq '{"components": (.components | map_values({"enabled": false}))}' "$CHART_VALUES" > "$CHART_ALL_OFF"

echo "==> Deploying only lens-bff + inbox (+ postgres/schema-registry/kafka) with local images, Inbox mock disabled"
helm upgrade --install resonance-flow "$CHART_DIR" \
  -f "$CHART_VALUES" \
  -f "$CHART_ALL_OFF" \
  --set components.lens-bff.enabled=true \
  --set components.lens-bff.image.repository=lens-bff \
  --set components.lens-bff.image.tag=local \
  --set components.lens-bff.image.pullPolicy=Never \
  --set components.lens-bff.env.INBOX_MOCK_ENABLED=false \
  --set components.inbox.enabled=true \
  --set components.inbox.image.repository=inbox \
  --set components.inbox.image.tag=local \
  --set components.inbox.image.pullPolicy=Never \
  --set components.schema-registry.enabled=true \
  --set postgresql.enabled=true \
  --namespace "$NAMESPACE" --create-namespace \
  --wait --timeout 600s

echo "==> Seeding one real tenant + user directly in Postgres (no API for this exists yet)"
PG_POD=$(kubectl get pod -n "$NAMESPACE" -l app.kubernetes.io/name=postgresql -o jsonpath='{.items[0].metadata.name}')
UUID_RE='[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
TENANT_ID=$(kubectl exec -n "$NAMESPACE" "$PG_POD" -- env PGPASSWORD=postgres psql -U postgres -d resonance_flow -tAc "
  INSERT INTO tenants (name, slug, status) VALUES ('LENS round trip', 'lens-round-trip', 'active')
  ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id;" \
  | grep -Eo "$UUID_RE" | head -1)
USER_ID=$(kubectl exec -n "$NAMESPACE" "$PG_POD" -- env PGPASSWORD=postgres psql -U postgres -d resonance_flow -tAc "
  INSERT INTO users (tenant_id, email, display_name, role, status)
  VALUES ('${TENANT_ID}', 'round-trip@test.local', 'Round Trip Tester', 'employee', 'active')
  ON CONFLICT (email) WHERE email IS NOT NULL
  DO UPDATE SET display_name = EXCLUDED.display_name RETURNING id;" \
  | grep -Eo "$UUID_RE" | head -1)
echo "    tenant: $TENANT_ID  user: $USER_ID"

echo "==> Creating one real task through Inbox's own API"
kubectl delete pod curl-seed curl-verify -n "$NAMESPACE" --ignore-not-found --wait >/dev/null 2>&1
kubectl run curl-seed -n "$NAMESPACE" --rm -i --restart=Never --image=curlimages/curl:latest --command -- sh -c "
  curl -sS -X POST http://resonance-flow-inbox:80/api/v1/tasks \
    -H 'X-Actor-Id: ${USER_ID}' -H 'Content-Type: application/json' \
    -d '{\"tenant_id\":\"${TENANT_ID}\",\"title\":\"round-trip-check\",\"owner_id\":\"${USER_ID}\"}'
"

echo "==> Fetching it back through lens-bff (real InboxClient, not mock)"
kubectl run curl-verify -n "$NAMESPACE" --restart=Never --image=curlimages/curl:latest \
  --labels="app.kubernetes.io/component=lens-gateway,app.kubernetes.io/instance=resonance-flow,app.kubernetes.io/name=resonance-flow" \
  --command -- sh -c "
  curl -sS --max-time 30 -w '\nHTTP_CODE:%{http_code}\n' 'http://resonance-flow-lens-bff:80/workspace/tasks?page=0&size=5' -H 'X-User-Id: ${USER_ID}'
" >/dev/null
kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/curl-verify -n "$NAMESPACE" --timeout=60s >/dev/null 2>&1 || true
RESULT=$(kubectl logs curl-verify -n "$NAMESPACE")
kubectl delete pod curl-verify -n "$NAMESPACE" --ignore-not-found >/dev/null 2>&1
echo "$RESULT"

if echo "$RESULT" | grep -q "HTTP_CODE:200" && echo "$RESULT" | grep -q "round-trip-check"; then
  echo "==> PASS: lens-bff fetched the real task back from the real Inbox"
else
  echo "==> FAIL: lens-bff did not return the task - see output above"
  exit 1
fi
