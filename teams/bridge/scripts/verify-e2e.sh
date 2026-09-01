#!/usr/bin/env bash
# End-to-end verification: triggers the intake endpoint, then confirms the
# same object_key/content_hash it returns actually landed in MinIO and was
# published to Kafka. Run this on the EC2 host (where docker compose is up).
#
# Usage: ./verify-e2e.sh [SOURCE_TYPE]
#   SOURCE_TYPE: EMAIL | SLACK | WHATSAPP | DRIVE | CALENDAR (default: EMAIL)
#
# Env overrides:
#   APP_URL            (default: http://localhost:8090)
#   MINIO_ACCESS_KEY   (default: minioadmin)
#   MINIO_SECRET_KEY   (default: minioadmin)
#   MINIO_BUCKET       (default: bridge-raw)
#   KAFKA_TOPIC        (default: bridge.raw.landed)
#   REDPANDA_CONTAINER (default: bridge-redpanda)
#   MINIO_CONTAINER    (default: bridge-minio)

set -euo pipefail

SOURCE_TYPE="${1:-EMAIL}"
APP_URL="${APP_URL:-http://localhost:8090}"
MINIO_USER="${MINIO_ACCESS_KEY:-minioadmin}"
MINIO_PASS="${MINIO_SECRET_KEY:-minioadmin}"
MINIO_BUCKET="${MINIO_BUCKET:-bridge-raw}"
KAFKA_TOPIC="${KAFKA_TOPIC:-bridge.raw.landed}"
REDPANDA_CONTAINER="${REDPANDA_CONTAINER:-bridge-redpanda}"
MINIO_CONTAINER="${MINIO_CONTAINER:-bridge-minio}"

for bin in curl jq docker; do
  command -v "$bin" >/dev/null 2>&1 || { echo "FAIL: '$bin' is required but not installed"; exit 1; }
done

NETWORK="$(docker inspect "$MINIO_CONTAINER" --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null || true)"
if [ -z "$NETWORK" ]; then
  echo "FAIL: could not determine the docker network of '$MINIO_CONTAINER' - is it running? (docker compose up -d)"
  exit 1
fi

echo "==> Triggering intake for source type: $SOURCE_TYPE"
RESPONSE="$(curl -sf -X POST "$APP_URL/api/bridge/intake/$SOURCE_TYPE/run")" || {
  echo "FAIL: request to $APP_URL failed - is the bridge app running there?"
  exit 1
}

COUNT="$(echo "$RESPONSE" | jq 'length')"
if [ "$COUNT" -eq 0 ]; then
  echo "FAIL: app returned zero events for source type $SOURCE_TYPE"
  exit 1
fi
echo "==> App returned $COUNT event(s)"
echo "$RESPONSE" | jq .

echo ""
echo "==> Reading back the last $COUNT message(s) from $KAFKA_TOPIC"
# Negative offset = read the last N messages (no arbitrary -n/timeout race:
# exactly $COUNT messages were just produced, so this reads exactly those).
KAFKA_DUMP="$(docker exec "$REDPANDA_CONTAINER" rpk topic consume "$KAFKA_TOPIC" \
  --brokers localhost:9092 -n "$COUNT" -o "-$COUNT" 2>/dev/null || true)"

PASS=0
FAIL=0

for i in $(seq 0 $((COUNT - 1))); do
  OBJECT_KEY="$(echo "$RESPONSE" | jq -r ".[$i].object_key")"
  CONTENT_HASH="$(echo "$RESPONSE" | jq -r ".[$i].content_hash")"

  echo ""
  echo "--- Event $((i + 1)): $OBJECT_KEY"

  if docker run --rm --network "$NETWORK" --entrypoint sh minio/mc -c \
      "mc alias set local http://$MINIO_CONTAINER:9000 '$MINIO_USER' '$MINIO_PASS' >/dev/null 2>&1 && mc stat 'local/$MINIO_BUCKET/$OBJECT_KEY' >/dev/null 2>&1"; then
    echo "  MinIO: PASS (object found at $MINIO_BUCKET/$OBJECT_KEY)"
    MINIO_OK=1
  else
    echo "  MinIO: FAIL (object not found)"
    MINIO_OK=0
  fi

  if echo "$KAFKA_DUMP" | grep -q "$CONTENT_HASH"; then
    echo "  Kafka: PASS (message with matching content_hash found in $KAFKA_TOPIC)"
    KAFKA_OK=1
  else
    echo "  Kafka: FAIL (no message with matching content_hash found)"
    KAFKA_OK=0
  fi

  if [ "$MINIO_OK" -eq 1 ] && [ "$KAFKA_OK" -eq 1 ]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
  fi
done

echo ""
echo "==================================="
echo "RESULT: $PASS passed, $FAIL failed (out of $COUNT)"
echo "==================================="

[ "$FAIL" -eq 0 ]
