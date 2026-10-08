#!/usr/bin/env bash
# Port-forwards a component's Service and curls its readiness path.
# Usage: smoke-test.sh <service_name> <local_port>   (expects $CHART_VALUES)
set -euo pipefail

SERVICE="$1"
LOCAL_PORT="$2"

HEALTH_PATH=$(yq eval ".components.${SERVICE}.readinessProbe.httpGet.path" "$CHART_VALUES")
# fullnameOverride is pinned to "resonance-flow" (values.yaml), so this is the
# same Service name ArgoCD deploys in prod.
SVC="svc/resonance-flow-${SERVICE}"
URL="http://localhost:${LOCAL_PORT}${HEALTH_PATH}"

PF_LOG="${RUNNER_TEMP:-/tmp}/port-forward-${SERVICE}.log"
kubectl -n "$SERVICE" port-forward "$SVC" "${LOCAL_PORT}:80" > "$PF_LOG" 2>&1 &
PF_PID=$!
# Free the port so it doesn't block the next run on the runner.
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 30); do
  curl -sf "$URL" && exit 0
  if ! kill -0 "$PF_PID" 2>/dev/null; then
    echo "port-forward to $SVC failed:" >&2; cat "$PF_LOG" >&2; exit 1
  fi
  sleep 1
done
echo "$URL not healthy after 30s; port-forward log:" >&2; cat "$PF_LOG" >&2; exit 1
