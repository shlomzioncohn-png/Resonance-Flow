#!/usr/bin/env bash
# Port-forwards a component's Service and curls its readiness path.
# Usage: smoke-test.sh <service_name>   (expects $CHART_VALUES)
set -euo pipefail

SERVICE="$1"

HEALTH_PATH=$(yq eval ".components.${SERVICE}.readinessProbe.httpGet.path" "$CHART_VALUES")
# fullnameOverride is pinned to "resonance-flow" (values.yaml), so this is the
# same Service name ArgoCD deploys in prod.
SVC="svc/resonance-flow-${SERVICE}"

source "$(dirname "${BASH_SOURCE[0]}")/../../scripts/port-forward.sh"
healthy() { curl -sf "http://127.0.0.1:${LOCAL_PORT}${HEALTH_PATH}"; }
# Free the port so it doesn't block the next run on the runner.
trap 'kill "${PF_PID:-}" 2>/dev/null || true' EXIT

if ! port_forward_until "$SERVICE" "$SVC" 80 "${RUNNER_TEMP:-/tmp}/port-forward-${SERVICE}.log" 30 1 healthy; then
  echo "$SVC${HEALTH_PATH} not healthy after 30s" >&2
  exit 1
fi
