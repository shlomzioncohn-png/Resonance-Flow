#!/usr/bin/env bash
# Checks that lens-bff's Inbox and Chain URLs are reachable inside the cluster.
# Network path only: InboxClient and ChainClient are still mocked.
#
# Usage: ./verify-inbox-chain-reachability.sh [--clean]
#   --clean   delete the kind cluster at the end (default: leave it running)
#
# Requires: docker, kind, helm, kubectl - all already on this machine.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

CLUSTER_NAME="lens-bff-verify-cluster"
NAMESPACE="lens-bff-verify"
CHART_DIR="teams/relay/helm/resonance-flow"
CHART_VALUES="${CHART_DIR}/values.yaml"
CLEAN=0

for arg in "$@"; do
  [ "$arg" = "--clean" ] && CLEAN=1
done

for bin in docker kind helm kubectl; do
  command -v "$bin" >/dev/null 2>&1 || { echo "FAIL: '$bin' is required but not installed"; exit 1; }
done

cleanup() {
  if [ "$CLEAN" -eq 1 ]; then
    echo "==> --clean: deleting kind cluster $CLUSTER_NAME"
    kind delete cluster --name "$CLUSTER_NAME" || true
  else
    echo "==> Leaving kind cluster $CLUSTER_NAME running (pass --clean to tear it down)"
  fi
}
trap cleanup EXIT

echo "==> Building local images (lens-bff, inbox, chain)"
docker build -t lens-bff:local teams/lens/bff
docker build -t inbox:local teams/inbox
docker build -t chain:local teams/Chain

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

for img in lens-bff inbox chain; do
  echo "==> Loading $img into kind"
  kind load docker-image "${img}:local" --name "$CLUSTER_NAME"
done

echo "==> Fetching chart dependencies"
helm dependency build "$CHART_DIR"

echo "==> Deploying lens-bff + inbox + chain (+ postgres/schema-registry/kafka) with local images"
helm upgrade --install resonance-flow "$CHART_DIR" \
  -f "$CHART_VALUES" \
  -f "$CHART_DIR/values/values-lens-bff.yaml" \
  --set components.lens-bff.image.repository=lens-bff \
  --set components.lens-bff.image.tag=local \
  --set components.lens-bff.image.pullPolicy=Never \
  --set components.inbox.enabled=true \
  --set components.inbox.image.repository=inbox \
  --set components.inbox.image.tag=local \
  --set components.inbox.image.pullPolicy=Never \
  --set components.chain.enabled=true \
  --set components.chain.image.repository=chain \
  --set components.chain.image.tag=local \
  --set components.chain.image.pullPolicy=Never \
  --set components.schema-registry.enabled=true \
  --set postgresql.enabled=true \
  --namespace "$NAMESPACE" --create-namespace \
  --wait --timeout 600s

echo "==> Reachability check from inside the cluster (same namespace lens-bff runs in)"
RESULT=$(kubectl run curl-verify -n "$NAMESPACE" --rm -i --restart=Never \
  --image=curlimages/curl:latest --command -- sh -c '
    set -e
    echo "--- inbox: $(curl -s -o /dev/null -w "%{http_code}" http://resonance-flow-inbox:80/actuator/health/liveness)"
    echo "--- chain: $(curl -s -o /dev/null -w "%{http_code}" http://resonance-flow-chain:80/health)"
  ')
echo "$RESULT"

if echo "$RESULT" | grep -q "inbox: 200" && echo "$RESULT" | grep -q "chain: 200"; then
  echo "==> PASS: both http://resonance-flow-inbox:80 and http://resonance-flow-chain:80 (lens-bff's exact configured addresses) are reachable and healthy"
else
  echo "==> FAIL: one or both addresses did not return 200 - see output above"
  exit 1
fi
