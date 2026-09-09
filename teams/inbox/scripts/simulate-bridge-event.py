#!/usr/bin/env python3
"""Publish a Bridge-shaped event onto bridge.structured.tasks and watch for the
task.created event Inbox produces back, against a real deployed stack (not
docker-compose) - see issue #227.

Bridge itself isn't deployed in this verification; this script stands in for it
by publishing a message shaped exactly like StructuredTaskEventDto
(teams/inbox/src/main/java/com/rnd/inbox/structuredtask/StructuredTaskEventDto.java).

Requires a real tenant row to already exist (tasks.tenant_id has a DB-level FK
to tenants.id) - pass one with --tenant-id.

Usage:
    pip install kafka-python
    python simulate-bridge-event.py \
        --bootstrap-servers localhost:19092 \
        --username inbox --password <sasl-password> \
        --tenant-id <existing-tenant-uuid>
"""
import argparse
import json
import sys
import time
import uuid

from kafka import KafkaProducer, KafkaConsumer

IN_TOPIC = "bridge.structured.tasks"
OUT_TOPIC = "task.created"


def build_event(tenant_id: str, source_ref: str) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "tenantId": tenant_id,
        "title": f"Issue #227 live round-trip verification ({source_ref})",
        "description": "Simulated Bridge event injected directly into Kafka per #227.",
        "sourceType": "GITHUB_ISSUE",
        "sourceRef": source_ref,
        "urgency": 3,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bootstrap-servers", default="localhost:19092")
    parser.add_argument("--username", default="inbox")
    parser.add_argument("--password", required=True)
    parser.add_argument("--tenant-id", required=True, help="Existing tenants.id UUID")
    parser.add_argument("--source-ref", default=None, help="Defaults to a fresh unique value")
    parser.add_argument("--timeout", type=int, default=90, help="Seconds to wait for task.created")
    args = parser.parse_args()

    source_ref = args.source_ref or f"simulate-bridge-event/{int(time.time())}"
    event = build_event(args.tenant_id, source_ref)

    common_kwargs = dict(
        bootstrap_servers=args.bootstrap_servers,
        security_protocol="SASL_PLAINTEXT",
        sasl_mechanism="PLAIN",
        sasl_plain_username=args.username,
        sasl_plain_password=args.password,
    )

    print(f"--- Publishing to {IN_TOPIC} ---")
    print(json.dumps(event, indent=2))
    producer = KafkaProducer(
        **common_kwargs,
        key_serializer=lambda k: k.encode("utf-8") if k else None,
        value_serializer=lambda v: json.dumps(v).encode("utf-8"),
    )
    future = producer.send(IN_TOPIC, key=event["eventId"], value=event)
    metadata = future.get(timeout=30)
    producer.flush()
    producer.close()
    print(f"Sent to {metadata.topic}[{metadata.partition}]@{metadata.offset}")

    print(f"\n--- Watching {OUT_TOPIC} for the resulting task.created event ---")
    consumer = KafkaConsumer(
        OUT_TOPIC,
        **common_kwargs,
        auto_offset_reset="latest",
        enable_auto_commit=False,
        group_id=f"simulate-bridge-event-watcher-{uuid.uuid4()}",
        consumer_timeout_ms=args.timeout * 1000,
        key_deserializer=lambda k: k.decode("utf-8") if k else None,
        value_deserializer=lambda v: v.decode("utf-8"),
    )

    deadline = time.time() + args.timeout
    for record in consumer:
        try:
            payload = json.loads(record.value)
        except (TypeError, json.JSONDecodeError):
            payload = record.value
        if isinstance(payload, dict) and payload.get("sourceRef") == source_ref:
            print(f"\nMATCH on task.created[{record.partition}]@{record.offset}:")
            print(json.dumps(payload, indent=2, default=str))
            consumer.close()
            return 0
        if time.time() > deadline:
            break

    consumer.close()
    print(f"\nNo task.created event with sourceRef={source_ref!r} seen within {args.timeout}s.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
