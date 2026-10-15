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
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.protobuf import ProtobufSerializer
from confluent_kafka.serialization import (
    MessageField,
    SerializationContext,
    StringSerializer,
)

from mind.messaging.config import MessagingSettings, load_settings

# Auto-register on first publish (the Confluent Java serializer default too).
# `false` + `use.latest.version` can't read source-format schemas back with this
# confluent-kafka version.
_SERIALIZER_CONF = {
    "auto.register.schemas": True,
    "use.deprecated.format": False,
}


class ProtobufProducer:
    def __init__(self, settings: MessagingSettings | None = None):
        self._settings = settings or load_settings()
        self._producer = Producer(self._settings.producer_conf())
        self._registry = SchemaRegistryClient({"url": self._settings.schema_registry_url})
        self._key_serializer = StringSerializer("utf_8")
        self._value_serializers: dict[type, ProtobufSerializer] = {}

    def _value_serializer(self, message_type: type) -> ProtobufSerializer:
        if message_type not in self._value_serializers:
            self._value_serializers[message_type] = ProtobufSerializer(
                message_type, self._registry, _SERIALIZER_CONF
            )
        return self._value_serializers[message_type]

    def publish(self, topic: str, message, key: str | None = None) -> None:
        """Serialize and send one message, then block until it's acked."""
        ctx = SerializationContext(topic, MessageField.VALUE)
        value = self._value_serializer(type(message))(message, ctx)

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
