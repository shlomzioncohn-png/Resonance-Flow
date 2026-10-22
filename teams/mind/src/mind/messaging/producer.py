"""ProtobufProducer - publish a Protobuf message to Kafka in the Confluent
wire format (schema-id header + payload), so the schema registry and any
registry-aware consumer can decode it.

MIND builds the message (from its extraction pipeline) and calls publish();
everything below is done.

    from mind.messaging import ProtobufProducer
    from mind.messaging.task_dependencies_proposed_event_pb2 import DependencyProposedEvent

    producer = ProtobufProducer()
    producer.publish("task.dependencies.proposed", DependencyProposedEvent(...), key=task_id)
"""

from __future__ import annotations

from typing import Self

from confluent_kafka import KafkaException, Producer
from confluent_kafka.schema_registry import SchemaRegistryClient, prefix_schema_id_serializer
from confluent_kafka.schema_registry.common.protobuf import _create_index_array
from confluent_kafka.schema_registry.common.serde import SchemaId
from confluent_kafka.serialization import (
    MessageField,
    SerializationContext,
    StringSerializer,
)
from google.protobuf.message import Message

from mind.messaging.config import MessagingSettings, load_settings


class ProtobufProducer:
    def __init__(self, settings: MessagingSettings | None = None):
        self._settings = settings or load_settings()
        self._settings.require_client_keys()
        self._producer = Producer(self._settings.producer_conf())
        self._registry = SchemaRegistryClient({"url": self._settings.schema_registry_url})
        self._key_serializer = StringSerializer("utf_8")
        self._schema_ids: dict[tuple[str, type[Message]], SchemaId] = {}

    def _schema_id(self, topic: str, message_type: type[Message]) -> SchemaId:
        if (topic, message_type) not in self._schema_ids:
            latest = self._registry.get_latest_version(f"{topic}-value")
            schema_id = SchemaId("PROTOBUF", latest.schema_id, latest.guid)
            schema_id.message_indexes = _create_index_array(message_type.DESCRIPTOR)
            self._schema_ids[(topic, message_type)] = schema_id
        return self._schema_ids[(topic, message_type)]

    def publish(self, topic: str, message, key: str | None = None) -> None:
        """Serialize and send one message, then block until it's acked."""
        ctx = SerializationContext(topic, MessageField.VALUE)
        value = prefix_schema_id_serializer(
            message.SerializeToString(), ctx, self._schema_id(topic, type(message))
        )

        delivery_error = None

        def _on_delivery(err, _msg):
            nonlocal delivery_error
            delivery_error = err

        self._producer.produce(
            topic=topic,
            key=self._key_serializer(key) if key is not None else None,
            value=value,
            on_delivery=_on_delivery,
        )
        self._producer.flush()

        if delivery_error is not None:
            raise KafkaException(delivery_error)

    def close(self) -> None:
        self._producer.flush()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc) -> None:
        self.close()
