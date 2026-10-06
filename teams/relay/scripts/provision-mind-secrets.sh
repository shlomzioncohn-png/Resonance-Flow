#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${1:?usage: provision-mind-secrets.sh <namespace>}"
PASSWORD="$(openssl rand -base64 24)"
SECRETS_FILE="$(mktemp)"
trap 'rm -f "$SECRETS_FILE"' EXIT

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$NAMESPACE" create secret generic resonance-flow-mind-db-credentials \
  --from-literal=password="$PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

cat > "$SECRETS_FILE" <<EOF
llm:
  api_key: ""
vector_store:
  password: "$PASSWORD"
object_storage:
  access_key: ""
  secret_key: ""
EOF

kubectl -n "$NAMESPACE" create secret generic resonance-flow-mind-secret \
  --from-file=secrets.yaml="$SECRETS_FILE" \
  --dry-run=client -o yaml | kubectl apply -f -
