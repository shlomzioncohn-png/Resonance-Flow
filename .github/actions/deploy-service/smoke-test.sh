#!/usr/bin/env bash
# Port-forwards a component's Service and curls its readiness path.
# Usage: smoke-test.sh <service_name>   (expects $CHART_VALUES)
set -euo pipefail

SERVICE="$1"

HEALTH_PATH=$(yq eval ".components.${SERVICE}.readinessProbe.httpGet.path" "$CHART_VALUES")
# fullnameOverride is pinned to "resonance-flow" (values.yaml), so this is the
# same Service name ArgoCD deploys in prod.
SVC="svc/resonance-flow-${SERVICE}"

# ":80" with no local port: the OS picks a free one at bind time, so parallel
# jobs on one host never fight over a fixed port. kubectl logs which one it got
# ("Forwarding from 127.0.0.1:<port> -> <pod port>").
PF_LOG="${RUNNER_TEMP:-/tmp}/port-forward-${SERVICE}.log"
# Create it now: the background job's redirect may not have run yet when sed
# first reads it, and a missing file would end the script (set -e).
: > "$PF_LOG"
kubectl -n "$SERVICE" port-forward "$SVC" :80 > "$PF_LOG" 2>&1 &
PF_PID=$!
# Free the port so it doesn't block the next run on the runner.
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT

LOCAL_PORT=""
for _ in $(seq 1 30); do
  if [ -z "$LOCAL_PORT" ]; then
    LOCAL_PORT=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) -> .*/\1/p' "$PF_LOG" | head -n 1)
  fi
  if [ -n "$LOCAL_PORT" ] && curl -sf "http://127.0.0.1:${LOCAL_PORT}${HEALTH_PATH}"; then
    exit 0
  fi
  if ! kill -0 "$PF_PID" 2>/dev/null; then
    echo "port-forward to $SVC failed:" >&2; cat "$PF_LOG" >&2; exit 1
  fi
  sleep 1
done
echo "$SVC${HEALTH_PATH} not healthy after 30s (local port: ${LOCAL_PORT:-none}); port-forward log:" >&2
cat "$PF_LOG" >&2
exit 1
