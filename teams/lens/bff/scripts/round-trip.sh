#!/usr/bin/env bash
# Seeds one real tenant/user/task through Inbox's own API, then confirms
# lens-bff fetches it back through its real InboxClient - not the mock.
# Assumes lens-bff + Inbox are already deployed and reachable in $NAMESPACE.
#
# Usage: NAMESPACE=<namespace> ./round-trip.sh

set -euo pipefail

NAMESPACE="${NAMESPACE:?NAMESPACE env var is required}"

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
INSTANCE=$(kubectl get pod -n "$NAMESPACE" -l app.kubernetes.io/component=lens-bff -o jsonpath='{.items[0].metadata.labels.app\.kubernetes\.io/instance}')
kubectl run curl-verify -n "$NAMESPACE" --restart=Never --image=curlimages/curl:latest \
  --labels="app.kubernetes.io/component=lens-gateway,app.kubernetes.io/instance=${INSTANCE},app.kubernetes.io/name=resonance-flow" \
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
