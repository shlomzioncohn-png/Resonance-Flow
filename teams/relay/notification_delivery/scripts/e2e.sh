#!/usr/bin/env bash
# End-to-end check of notification-delivery against an already-deployed resonance-flow release
# (real Kafka + Schema Registry from the shared chart):
#   1. registers the schemas the way the cluster does - by running the schema-sync-hook chart,
#      fed this checkout's shared/proto + teams/relay/ci/schema-subjects.tsv
#   2. checks the NetworkPolicy: only lens-bff pods may reach the service
#   3. runs the NotificationDeliveryE2E client (test/java/pipe/e2e) as a pod in the cluster,
#      labelled as lens-bff - Kafka only advertises its in-cluster DNS name, so it can't run
#      from outside through a port-forward
#
# Usage: e2e.sh <namespace>
#
# Env overrides:
#   KIND_CLUSTER      kind cluster to load the client image into (default: none - E2E_IMAGE must be pullable)
#   E2E_IMAGE         client image (default: notification-delivery-e2e:local)
#   SKIP_BUILD=1      don't build E2E_IMAGE, use it as-is
#   SKIP_NETPOL=1     skip step 2, for a CNI that doesn't enforce NetworkPolicy
#   E2E_TIMEOUT       seconds to wait for the client pod (default: 600)
set -euo pipefail

NS="${1:?usage: e2e.sh <namespace>}"
E2E_IMAGE="${E2E_IMAGE:-notification-delivery-e2e:local}"
E2E_TIMEOUT="${E2E_TIMEOUT:-600}"

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(git -C "$MODULE_DIR" rev-parse --show-toplevel)"
# fullnameOverride is pinned to "resonance-flow" (values.yaml), same as smoke-test.sh assumes.
FULLNAME="resonance-flow"
SERVICE="${FULLNAME}-notification-delivery"
CONFIG_MAP="${SERVICE}-config"
HOOK_RELEASE="nd-e2e-schema-sync"
SUFFIX="$(date +%s)"
CLIENT_POD="nd-e2e-client-${SUFFIX}"

for bin in kubectl helm git docker; do
  command -v "$bin" >/dev/null 2>&1 || { echo "FAIL: '$bin' is required but not installed"; exit 1; }
done

k() { kubectl -n "$NS" "$@"; }

TMP_DIR="$(mktemp -d)"
cleanup() {
  k delete pod "$CLIENT_POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  helm -n "$NS" uninstall "$HOOK_RELEASE" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "==> Waiting for ${SERVICE} to be rolled out in namespace ${NS}"
k rollout status "deploy/${SERVICE}" --timeout=300s

# Name/instance labels of this release, so test pods can be matched by the NetworkPolicy.
LABEL_NAME="$(k get "deploy/${SERVICE}" -o jsonpath='{.spec.selector.matchLabels.app\.kubernetes\.io/name}')"
LABEL_INSTANCE="$(k get "deploy/${SERVICE}" -o jsonpath='{.spec.selector.matchLabels.app\.kubernetes\.io/instance}')"
AS_LENS_BFF="app.kubernetes.io/name=${LABEL_NAME},app.kubernetes.io/instance=${LABEL_INSTANCE},app.kubernetes.io/component=lens-bff"

# Same registry URL the service itself is configured with.
REGISTRY_URL="$(k get configmap "$CONFIG_MAP" -o jsonpath='{.data.config\.yaml}' \
  | sed -n 's/.*schema\.registry\.url:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}.*/\1/p')"
[ -n "$REGISTRY_URL" ] || { echo "FAIL: no schema.registry.url in configmap ${CONFIG_MAP}"; exit 1; }

echo ""
echo "==> 1. Registering schemas through schema-sync-hook (registry: ${REGISTRY_URL})"
# The chart's files/ are copies refreshed only after merge (relay-schema-sync.yaml) - refresh them
# here the same way, so this run registers the checkout's schemas, not the last synced ones.
HOOK_CHART="${TMP_DIR}/schema-sync-hook"
cp -r "${REPO_ROOT}/teams/relay/schema-sync-hook" "$HOOK_CHART"
mkdir -p "${HOOK_CHART}/files/schemas" "${HOOK_CHART}/files/ci"
cp "${REPO_ROOT}"/shared/proto/*.proto "${HOOK_CHART}/files/schemas/"
cp "${REPO_ROOT}/teams/relay/ci/check-schema-compatibility.sh" "${REPO_ROOT}/teams/relay/ci/register-schema.sh" "${HOOK_CHART}/files/ci/"
cp "${REPO_ROOT}/teams/relay/ci/schema-subjects.tsv" "${HOOK_CHART}/files/"
# A Windows checkout has CRLF working copies; the hook runs them under Linux bash.
find "${HOOK_CHART}/files" -type f -exec sed -i 's/\r$//' {} +

# The hook's Job is immutable - start from a clean release every run.
helm -n "$NS" uninstall "$HOOK_RELEASE" --wait >/dev/null 2>&1 || true
if ! helm -n "$NS" upgrade --install "$HOOK_RELEASE" "$HOOK_CHART" \
     --set schemaRegistryUrl="$REGISTRY_URL" \
     --wait --wait-for-jobs --timeout 300s; then
  echo "FAIL: schema-sync-hook Job did not succeed:"
  k logs "job/${HOOK_RELEASE}-schema-sync-hook" --tail=100 2>/dev/null || k logs -l "app.kubernetes.io/instance=${HOOK_RELEASE}" --tail=100 || true
  exit 1
fi
k logs -l "app.kubernetes.io/instance=${HOOK_RELEASE}" --tail=-1 | grep -E '^(===|Registered|Compatible|New subject)' || true

NETPOL_FAILED=0
if [ "${SKIP_NETPOL:-}" = "1" ]; then
  echo ""
  echo "==> 2. NetworkPolicy check skipped (SKIP_NETPOL=1)"
else
  echo ""
  echo "==> 2. NetworkPolicy: only lens-bff may reach ${SERVICE}"
  probe() {  # <pod-name> <labels> -> prints the HTTP code, or 000 when the connection is blocked
    k run "$1" --image=curlimages/curl:8.10.1 --restart=Never --labels="$2" --quiet --rm -i --command -- \
      curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://${SERVICE}/healthz" 2>/dev/null || true
  }
  ALLOWED="$(probe "nd-e2e-netpol-allowed-${SUFFIX}" "$AS_LENS_BFF")"
  DENIED="$(probe "nd-e2e-netpol-denied-${SUFFIX}" "e2e=notification-delivery")"
  if [ "$ALLOWED" = "200" ]; then echo "PASS: a lens-bff pod gets through (HTTP ${ALLOWED})"
  else echo "FAIL: a lens-bff pod could not reach ${SERVICE} (got '${ALLOWED}')"; NETPOL_FAILED=1; fi
  # 000 = curl ran and the connection timed out; empty = the probe pod itself never ran.
  if [ "$DENIED" = "000" ]; then echo "PASS: any other pod is blocked"
  elif [ -z "$DENIED" ]; then echo "FAIL: the unlabelled probe pod produced no result"; NETPOL_FAILED=1
  else echo "FAIL: a non-lens-bff pod reached ${SERVICE} (HTTP ${DENIED}) - NetworkPolicy not enforced?"; NETPOL_FAILED=1; fi
fi

echo ""
echo "==> 3. Running the E2E client (${E2E_IMAGE})"
if [ "${SKIP_BUILD:-}" != "1" ]; then
  docker build -q --target e2e --build-context "shared=${REPO_ROOT}/shared" -t "$E2E_IMAGE" "$MODULE_DIR" >/dev/null
fi
if [ -n "${KIND_CLUSTER:-}" ]; then
  kind load docker-image "$E2E_IMAGE" --name "$KIND_CLUSTER" >/dev/null
fi

k apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: ${CLIENT_POD}
  labels:
    app.kubernetes.io/name: ${LABEL_NAME}
    app.kubernetes.io/instance: ${LABEL_INSTANCE}
    # Impersonates lens-bff - the only caller the NetworkPolicy lets in.
    app.kubernetes.io/component: lens-bff
    e2e: notification-delivery
spec:
  restartPolicy: Never
  containers:
    - name: e2e
      image: ${E2E_IMAGE}
      imagePullPolicy: IfNotPresent
      env:
        - name: E2E_SERVICE_URL
          value: http://${SERVICE}
        - name: E2E_CONFIG_FILE
          value: /config/config.yaml
      volumeMounts:
        - name: service-config
          mountPath: /config
      resources:
        requests: { cpu: 100m, memory: 256Mi }
        limits: { cpu: "1", memory: 512Mi }
  volumes:
    - name: service-config
      configMap:
        name: ${CONFIG_MAP}
EOF

# Stream the logs once the container has started, then read the exit code.
DEADLINE=$(( $(date +%s) + E2E_TIMEOUT ))
until [ "$(k get pod "$CLIENT_POD" -o jsonpath='{.status.phase}')" != "Pending" ]; do
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "FAIL: client pod still Pending after ${E2E_TIMEOUT}s"; k describe pod "$CLIENT_POD" | tail -20; exit 1
  fi
  sleep 2
done
k logs -f "$CLIENT_POD" || true
until PHASE="$(k get pod "$CLIENT_POD" -o jsonpath='{.status.phase}')" && [ "$PHASE" = "Succeeded" ] || [ "$PHASE" = "Failed" ]; do
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then echo "FAIL: client pod did not finish within ${E2E_TIMEOUT}s"; exit 1; fi
  sleep 2
done

echo ""
if [ "$PHASE" = "Succeeded" ] && [ "$NETPOL_FAILED" -eq 0 ]; then
  echo "E2E PASSED"
  exit 0
fi
echo "E2E FAILED (client pod: ${PHASE}, NetworkPolicy check: $([ "$NETPOL_FAILED" -eq 0 ] && echo ok || echo failed))"
exit 1
