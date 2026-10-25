"""RELAY <-> MIND: mind.messaging's config loads from the mounted files, and
a message round-trips through the Confluent Protobuf wire format (the Kafka
hop itself is exercised by the live run, see ../scripts/round-trip.py).
"""

from __future__ import annotations

import pytest
import yaml

from mind.config_file import ConfigFileError
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


def test_kafka_sasl_password_comes_from_its_mounted_file_first(tmp_path, monkeypatch):
    cfg = tmp_path / "config.yaml"
    cfg.write_text("kafka:\n  bootstrap_servers: broker:9092\n")
    sec = tmp_path / "secrets.yaml"
    sec.write_text("kafka:\n  sasl: { mechanism: PLAIN, username: u, password: stale }\n")
    sasl_dir = tmp_path / "kafka-sasl"
    sasl_dir.mkdir()
    (sasl_dir / "client-passwords").write_text("fresh\n")
    monkeypatch.setenv("CONFIG_FILE_PATH", str(cfg))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(sec))
    monkeypatch.setenv("KAFKA_SASL_SECRETS_DIR", str(sasl_dir))

    assert load_settings().sasl.password == "fresh"

    (sasl_dir / "client-passwords").unlink()
    assert load_settings().sasl.password == "stale"


def test_config_missing_config_file_fails(tmp_path, monkeypatch):
    # #481: no silent fallback -- a missing config.yaml stops the caller.
    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "nope.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "nope2.yaml"))
    with pytest.raises(ConfigFileError, match="does not exist"):
        load_settings()


def test_config_missing_secrets_file_is_safe(tmp_path, monkeypatch):
    # The Secret is mounted optional: no secrets.yaml just means no SASL.
    cfg = tmp_path / "config.yaml"
    cfg.write_text("kafka:\n  bootstrap_servers: broker:9092\n")
    monkeypatch.setenv("CONFIG_FILE_PATH", str(cfg))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "nope2.yaml"))
    s = load_settings()
    assert s.bootstrap_servers == "broker:9092"
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

    # #481: config.yaml must exist (load_settings is strict) and a client needs
    # both kafka keys; secrets.yaml may not exist.
    _kafka_config(
        tmp_path, monkeypatch, bootstrap_servers=_BOOTSTRAP, schema_registry_url=_REGISTRY
    )
    monkeypatch.setattr(consumer_module, "Consumer", _RecordingConsumer)

    built = consumer_module.ProtobufConsumer("g", ["t"], conf_overrides=overrides)

    conf = built._consumer.conf
    assert conf["group.id"] == "g"
    assert conf.get("max.poll.interval.ms") == expected


# --- a Kafka client needs both kafka keys (#481 review) --------------------

_BOOTSTRAP = "broker:9092"
_REGISTRY = "http://sr:8080/apis/ccompat/v6"


def _kafka_config(tmp_path, monkeypatch, **kafka):
    cfg = tmp_path / "config.yaml"
    cfg.write_text(
        yaml.safe_dump({"kafka": kafka} if kafka else {"service": {"log_level": "INFO"}})
    )
    monkeypatch.setenv("CONFIG_FILE_PATH", str(cfg))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "nope.yaml"))


def test_no_kafka_section_means_kafka_is_off_and_load_settings_does_not_raise(
    tmp_path, monkeypatch
):
    # Empty values are how #924 tells "Kafka is off" (a local run without a broker).
    _kafka_config(tmp_path, monkeypatch)

    s = load_settings()

    assert (s.bootstrap_servers, s.schema_registry_url) == ("", "")
    assert s.kafka_configured() is False


def test_both_kafka_keys_set_means_kafka_is_configured(tmp_path, monkeypatch):
    _kafka_config(
        tmp_path, monkeypatch, bootstrap_servers=_BOOTSTRAP, schema_registry_url=_REGISTRY
    )

    assert load_settings().kafka_configured() is True


@pytest.mark.parametrize(
    ("kafka", "missing"),
    [
        ({"bootstrap_servers": _BOOTSTRAP}, "kafka.schema_registry_url"),
        ({"schema_registry_url": _REGISTRY}, "kafka.bootstrap_servers"),
        ({"bootstrap_servers": _BOOTSTRAP, "schema_registry_url": ""}, "kafka.schema_registry_url"),
        ({"bootstrap_servers": None, "schema_registry_url": _REGISTRY}, "kafka.bootstrap_servers"),
    ],
)
def test_only_one_kafka_key_set_is_a_broken_config_not_kafka_off(
    tmp_path, monkeypatch, kafka, missing
):
    _kafka_config(tmp_path, monkeypatch, **kafka)
    s = load_settings()  # still doesn't raise here

    with pytest.raises(ConfigFileError, match=rf"{missing} is missing .* incomplete Kafka config"):
        s.kafka_configured()


@pytest.mark.parametrize("client", ["producer", "consumer"])
@pytest.mark.parametrize(
    ("kafka", "match"),
    [
        ({}, r"kafka\.bootstrap_servers and kafka\.schema_registry_url are missing"),
        ({"bootstrap_servers": _BOOTSTRAP}, r"kafka\.schema_registry_url is missing"),
        ({"schema_registry_url": _REGISTRY}, r"kafka\.bootstrap_servers is missing"),
    ],
)
def test_building_a_client_without_its_kafka_keys_fails_naming_them(
    tmp_path, monkeypatch, client, kafka, match
):
    from mind.messaging import consumer as consumer_module
    from mind.messaging import producer as producer_module

    _kafka_config(tmp_path, monkeypatch, **kafka)
    # Fail before any librdkafka object exists -- not a warning and a silent poll.
    monkeypatch.setattr(consumer_module, "Consumer", _never_built)
    monkeypatch.setattr(producer_module, "Producer", _never_built)

    with pytest.raises(ConfigFileError, match=match):
        if client == "producer":
            producer_module.ProtobufProducer()
        else:
            consumer_module.ProtobufConsumer("g", ["t"])


def test_a_producer_is_built_when_both_kafka_keys_are_set(tmp_path, monkeypatch):
    from mind.messaging import producer as producer_module

    _kafka_config(
        tmp_path, monkeypatch, bootstrap_servers=_BOOTSTRAP, schema_registry_url=_REGISTRY
    )
    monkeypatch.setattr(producer_module, "Producer", lambda conf: conf)

    built = producer_module.ProtobufProducer()

    assert built._producer["bootstrap.servers"] == _BOOTSTRAP


def _never_built(*args, **kwargs):
    raise AssertionError("a Kafka client was built despite a missing kafka key")
