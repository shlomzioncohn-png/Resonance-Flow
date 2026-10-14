"""RELAY <-> MIND: mind.messaging's config loads from the mounted files, and
a message round-trips through the Confluent Protobuf wire format (the Kafka
hop itself is exercised by the live run, see ../scripts/round-trip.py).
"""

from __future__ import annotations

import pytest

from mind.messaging.config import load_settings
from mind.messaging.mind_task_dependencies_pb2 import TaskDependenciesDetected

# --- config from the mounted files -----------------------------------------

def test_config_reads_kafka_from_the_mounted_files(tmp_path, monkeypatch):
    cfg = tmp_path / "config.yaml"
    cfg.write_text(
        "kafka:\n"
        "  bootstrap_servers: broker:9092\n"
        "  schema_registry_url: http://sr:8080/apis/ccompat/v6\n"
        "  producer: { acks: all, retries: 5 }\n"
    )
    sec = tmp_path / "secrets.yaml"
    sec.write_text("kafka:\n  sasl: { mechanism: SCRAM-SHA-256, username: u, password: p }\n")
    monkeypatch.setenv("CONFIG_FILE_PATH", str(cfg))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(sec))

    s = load_settings()
    assert s.bootstrap_servers == "broker:9092"
    assert s.schema_registry_url == "http://sr:8080/apis/ccompat/v6"
    assert s.retries == 5
    assert s.sasl.enabled
    assert s.producer_conf()["security.protocol"] == "SASL_PLAINTEXT"
    assert s.consumer_conf("g")["group.id"] == "g"


def test_config_missing_files_is_safe(tmp_path, monkeypatch):
    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "nope.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "nope2.yaml"))
    s = load_settings()
    assert s.bootstrap_servers == ""
    assert not s.sasl.enabled
    assert s.producer_conf()["acks"] == "all"


# --- the consumer reads the Confluent wire format ------------------------

def test_deserializer_reads_a_confluent_framed_message():
    """A Confluent Protobuf value = magic byte 0x00, 4-byte schema id, the
    message-index varint (0x00 for the first top-level message), then the
    Protobuf bytes. The consumer's ProtobufDeserializer must decode that."""
    import struct

    protobuf = pytest.importorskip("confluent_kafka.schema_registry.protobuf")
    from confluent_kafka.serialization import MessageField, SerializationContext

    event = TaskDependenciesDetected(
        tenant_id="t-1", task_id="task-1", depends_on_task_ids=["task-0"],
        estimated_duration_minutes=30,
    )
    frame = b"\x00" + struct.pack(">I", 1) + b"\x00" + event.SerializeToString()

    deserializer = protobuf.ProtobufDeserializer(
        TaskDependenciesDetected, {"use.deprecated.format": False}
    )
    back = deserializer(
        frame, SerializationContext("task.dependencies.detected", MessageField.VALUE)
    )

    assert back.task_id == "task-1"
    assert back.tenant_id == "t-1"
    assert list(back.depends_on_task_ids) == ["task-0"]
    assert back.estimated_duration_minutes == 30


def test_module_classes_construct():
    from mind.messaging import ProtobufConsumer, ProtobufProducer
    # constructing needs a broker/registry, so just assert the API surface
    assert hasattr(ProtobufProducer, "publish")
    assert hasattr(ProtobufConsumer, "run")
