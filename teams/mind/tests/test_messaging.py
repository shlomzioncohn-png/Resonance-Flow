"""RELAY <-> MIND: mind.messaging's config loads from the mounted files, and
a message round-trips through the Confluent Protobuf wire format (the Kafka
hop itself is exercised by the live run, see ../scripts/round-trip.py).
"""

from __future__ import annotations

import pytest

from mind.messaging.config import load_settings
from mind.messaging.task_dependencies_proposed_event_pb2 import (
    Dependency,
    DependencyProposedEvent,
)

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

    event = DependencyProposedEvent(
        event_id="evt-1", task_id="task-1",
        dependencies=[Dependency(depends_on_task_id="task-0", confidence=0.75)],
    )
    frame = b"\x00" + struct.pack(">I", 1) + b"\x00" + event.SerializeToString()

    deserializer = protobuf.ProtobufDeserializer(
        DependencyProposedEvent, {"use.deprecated.format": False}
    )
    back = deserializer(
        frame, SerializationContext("task.dependencies.proposed", MessageField.VALUE)
    )

    assert back.event_id == "evt-1"
    assert back.task_id == "task-1"
    assert [(d.depends_on_task_id, d.confidence) for d in back.dependencies] == [
        ("task-0", 0.75)
    ]


def test_module_classes_construct():
    from mind.messaging import ProtobufConsumer, ProtobufProducer
    # constructing needs a broker/registry, so just assert the API surface
    assert hasattr(ProtobufProducer, "publish")
    assert hasattr(ProtobufConsumer, "run")


class _RecordingConsumer:
    """Stands in for confluent_kafka.Consumer: keeps the conf it was built with."""

    def __init__(self, conf: dict) -> None:
        self.conf = conf

    def subscribe(self, topics: list[str]) -> None:
        pass


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        (None, None),
        ({"max.poll.interval.ms": 1_800_000}, 1_800_000),
    ],
)
def test_a_consumer_can_override_its_conf(tmp_path, monkeypatch, overrides, expected):
    # #349's handler can run OCR and ASR for minutes, past Kafka's default
    # max.poll.interval.ms; other consumers keep the defaults untouched.
    from mind.messaging import consumer as consumer_module

    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "nope.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "nope2.yaml"))
    monkeypatch.setattr(consumer_module, "Consumer", _RecordingConsumer)

    built = consumer_module.ProtobufConsumer("g", ["t"], conf_overrides=overrides)

    conf = built._consumer.conf
    assert conf["group.id"] == "g"
    assert conf.get("max.poll.interval.ms") == expected
