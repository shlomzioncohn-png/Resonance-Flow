#!/usr/bin/env bash
# Runs the same checks as lens-gateway-ci.yaml's `cd` job (service-cd.yaml -> ci-java +
# deploy-service), against a local kind cluster, so this doesn't have to be validated by
# waiting on the self-hosted CI runner. Skips the two steps that only make sense in CI:
# pushing the image to GHCR and opening the manifest-bump PR (service-update-manifest.yaml).
#
# Usage: ./local-cd-test.sh [--clean]
#   --clean   delete the kind cluster at the end (default: leave it running, so the
#             next run skips cluster creation - that's most of the time this saves)
#
# Requires: docker, kind, helm, kubectl, yq - all already on this machine.

set -euo pipefail
export MSYS_NO_PATHCONV=1

SERVICE_NAME="lens-gateway"
SERVICE_DIR="teams/lens/gateway"
CONTAINER_PORT=8080
CLUSTER_NAME="${SERVICE_NAME}-cluster"
CHART_DIR="teams/relay/helm/resonance-flow"
CHART_VALUES="${CHART_DIR}/values.yaml"
CLEAN=0

for arg in "$@"; do
  [ "$arg" = "--clean" ] && CLEAN=1
done

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

for bin in docker kind helm kubectl yq; do
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

echo "==> [ci-java] mvn -B test (via Docker, same image CI uses)"
docker run --rm --network host \
  -v "$PWD:/workspace" -w "/workspace/${SERVICE_DIR}" \
  -v "$HOME/.m2:/root/.m2" \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -B test

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  echo "==> kind cluster $CLUSTER_NAME already exists, reusing it"
else
  echo "==> Creating kind cluster $CLUSTER_NAME"
  kind create cluster --name "$CLUSTER_NAME"
fi

echo "==> Fetching chart dependencies"
helm dependency build "$CHART_DIR"

echo "==> [setup-service-infra] deploying chart with $SERVICE_NAME disabled (infra only)"
helm upgrade --install "$SERVICE_NAME" "$CHART_DIR" \
  -f "$CHART_VALUES" \
  -f "$CHART_DIR/values/values-${SERVICE_NAME}.yaml" \
  --set components.${SERVICE_NAME}.enabled=false \
  --namespace "$SERVICE_NAME" --create-namespace \
  --wait --timeout 300s

echo "==> [deploy-service] building local image"
docker build -t "${SERVICE_NAME}:local" "$SERVICE_DIR"

echo "==> Loading image into kind"
kind load docker-image "${SERVICE_NAME}:local" --name "$CLUSTER_NAME"

echo "==> Deploying chart with the built image (smoke test)"
if ! helm upgrade --install "$SERVICE_NAME" "$CHART_DIR" \
  -f "$CHART_VALUES" \
  -f "$CHART_DIR/values/values-${SERVICE_NAME}.yaml" \
  --set components.${SERVICE_NAME}.image.repository="$SERVICE_NAME" \
  --set components.${SERVICE_NAME}.image.tag=local \
  --set components.${SERVICE_NAME}.image.pullPolicy=Never \
  --namespace "$SERVICE_NAME" --create-namespace \
  --wait --timeout 420s; then
  echo "==> Deploy failed, dumping diagnostics"
  kubectl -n "$SERVICE_NAME" get pods -o wide
  kubectl -n "$SERVICE_NAME" describe pods
  kubectl -n "$SERVICE_NAME" get events --sort-by=.lastTimestamp
  for pod in $(kubectl -n "$SERVICE_NAME" get pods -o jsonpath='{.items[*].metadata.name}'); do
    echo "--- logs: $pod"
    kubectl -n "$SERVICE_NAME" logs "$pod" --all-containers --tail=200 || true
  done
  exit 1
fi

echo "==> Smoke test"
HEALTH_PATH=$(yq eval ".components.${SERVICE_NAME}.readinessProbe.httpGet.path" "$CHART_VALUES")
kubectl -n "$SERVICE_NAME" port-forward "svc/${SERVICE_NAME}-resonance-flow-${SERVICE_NAME}" "${CONTAINER_PORT}:80" &
PF_PID=$!
sleep 5
if curl -sf "http://localhost:${CONTAINER_PORT}${HEALTH_PATH}" > /dev/null; then
  echo "==> PASS: $SERVICE_NAME is up and healthy at ${HEALTH_PATH}"
else
  echo "==> FAIL: health check against http://localhost:${CONTAINER_PORT}${HEALTH_PATH} failed"
  kill $PF_PID 2>/dev/null || true
  exit 1
fi
kill $PF_PID 2>/dev/null || true
