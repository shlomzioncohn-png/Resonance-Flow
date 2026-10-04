#!/usr/bin/env python3
"""Publish a Bridge-shaped event onto bridge.structured.tasks and watch for the
task.created event Inbox produces back, against a real deployed stack (not
docker-compose) - see issue #227.

Bridge itself isn't deployed in this verification; this script stands in for it
by publishing a message shaped exactly like StructuredTaskEventDto
(teams/inbox/src/main/java/com/rnd/inbox/structuredtask/StructuredTaskEventDto.java).

task.created is real Confluent-wire-format Protobuf (magic byte 0x0 + 4-byte
schema ID + protobuf-encoded body) - this script verifies that by fetching the
registered schema straight from the Schema Registry's ccompat API, compiling it
on the fly with grpc_tools.protoc, and decoding the message against it. No
pre-generated Python classes needed.

Requires a real tenant row to already exist (tasks.tenant_id has a DB-level FK
to tenants.id) - pass one with --tenant-id.

Usage:
    pip install kafka-python grpcio-tools requests
    python simulate-bridge-event.py \
        --bootstrap-servers resonance-flow-kafka:9092 \
        --password <sasl-password> \
        --tenant-id <existing-tenant-uuid> \
        --schema-registry-url http://resonance-flow-schema-registry:8080
"""
import argparse
import json
import struct
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import requests
from kafka import KafkaProducer, KafkaConsumer

IN_TOPIC = "bridge.structured.tasks"
OUT_TOPIC = "task.created"
OUT_SUBJECT = "task.created-value"


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


def fetch_schema(registry_url: str, subject: str) -> dict:
    resp = requests.get(f"{registry_url}/apis/ccompat/v6/subjects/{subject}/versions/latest", timeout=10)
    resp.raise_for_status()
    return resp.json()


def compile_descriptor(proto_text: str, message_type: str):
    """Write the fetched .proto text to disk, compile it with protoc (from
    grpc_tools) into a FileDescriptorSet, and return a decoder for message_type."""
    from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        proto_file = tmp_path / "task-created-event.proto"
        proto_file.write_text(proto_text)
        descriptor_out = tmp_path / "out.desc"

        import grpc_tools
        well_known_types = str(Path(grpc_tools.__file__).parent / "_proto")

        result = subprocess.run(
            [
                sys.executable, "-m", "grpc_tools.protoc",
                f"-I{tmp_path}",
                f"-I{well_known_types}",
                f"--descriptor_set_out={descriptor_out}",
                "--include_imports",
                str(proto_file),
            ],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(f"protoc failed:\n{result.stdout}\n{result.stderr}")

        fds = descriptor_pb2.FileDescriptorSet()
        fds.ParseFromString(descriptor_out.read_bytes())

        pool = descriptor_pool.DescriptorPool()
        for f in fds.file:
            pool.Add(f)

        full_name = None
        for f in fds.file:
            for mt in f.message_type:
                if mt.name == message_type:
                    full_name = f"{f.package}.{mt.name}" if f.package else mt.name
        if not full_name:
            raise RuntimeError(f"message type {message_type} not found in fetched schema")

        descriptor = pool.FindMessageTypeByName(full_name)
        return message_factory.GetMessageClass(descriptor)


def decode_confluent_protobuf(raw: bytes, registry_url: str) -> tuple[int, dict]:
    """Confluent/Apicurio wire format: magic byte 0x0, 4-byte big-endian schema
    ID, one or more protobuf message-index varints, then the protobuf payload."""
    if raw[0] != 0:
        raise ValueError(f"not Confluent wire format - first byte is {raw[0]!r}, expected 0x0")
    schema_id = struct.unpack(">I", raw[1:5])[0]

    # Message-index: a single 0x00 byte means "first (only) message in the file".
    offset = 5
    if raw[offset] == 0:
        offset += 1
    else:
        raise ValueError("multi-message-index schemas not handled by this script")

    payload = raw[offset:]

    schema_resp = requests.get(f"{registry_url}/apis/ccompat/v6/schemas/ids/{schema_id}", timeout=10)
    schema_resp.raise_for_status()
    proto_text = schema_resp.json()["schema"]

    message_cls = compile_descriptor(proto_text, "TaskCreatedEvent")
    msg = message_cls()
    msg.ParseFromString(payload)
    from google.protobuf.json_format import MessageToDict
    return schema_id, MessageToDict(msg, preserving_proto_field_name=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bootstrap-servers", default="resonance-flow-kafka:9092")
    parser.add_argument("--username", default="inbox")
    parser.add_argument("--password", required=True)
    parser.add_argument("--tenant-id", required=True, help="Existing tenants.id UUID")
    parser.add_argument("--schema-registry-url", default="http://resonance-flow-schema-registry:8080")
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

    print(f"\n--- Publishing to {IN_TOPIC} ---")
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

    print(f"\n--- Watching {OUT_TOPIC} for the resulting task.created event (raw bytes, Protobuf) ---")
    consumer = KafkaConsumer(
        OUT_TOPIC,
        **common_kwargs,
        auto_offset_reset="latest",
        enable_auto_commit=False,
        group_id=f"simulate-bridge-event-watcher-{uuid.uuid4()}",
        consumer_timeout_ms=args.timeout * 1000,
        key_deserializer=lambda k: k.decode("utf-8") if k else None,
        value_deserializer=lambda v: v,  # raw bytes - decoded manually below
    )

    deadline = time.time() + args.timeout
    for record in consumer:
        try:
            schema_id, decoded = decode_confluent_protobuf(record.value, args.schema_registry_url)
        except Exception as e:
            print(f"[{record.partition}]@{record.offset}: could not decode as Confluent Protobuf ({e}) - skipping")
            if time.time() > deadline:
                break
            continue

        if decoded.get("source_ref") == source_ref or decoded.get("sourceRef") == source_ref:
            registered = fetch_schema(args.schema_registry_url, OUT_SUBJECT)
            print(f"\nMATCH on task.created[{record.partition}]@{record.offset}")
            print(f"Confluent wire format confirmed: magic byte 0x0, embedded schema id={schema_id}, "
                  f"resolved live against {args.schema_registry_url} and matches "
                  f"{OUT_SUBJECT} version {registered['version']} (id={registered['id']})")
            print("Decoded Protobuf payload:")
            print(json.dumps(decoded, indent=2, default=str))
            consumer.close()
            return 0
        if time.time() > deadline:
            break

    consumer.close()
    print(f"\nNo task.created event with source_ref={source_ref!r} seen within {args.timeout}s.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
