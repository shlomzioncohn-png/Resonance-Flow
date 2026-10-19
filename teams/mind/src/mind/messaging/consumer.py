"""ProtobufConsumer - read Confluent-wire-format Protobuf messages off one or
more topics, deserialize them to a given Protobuf type, and hand each to a
handler. Offsets are committed only after the handler returns.

MIND writes the handler (its business logic) and picks the topic/type:

    from mind.messaging import ProtobufConsumer
    from mind.messaging.task_created_event_pb2 import TaskCreatedEvent

    def handle(event, msg):
        ...  # MIND's logic

    consumer = ProtobufConsumer(group_id="mind-ingestion", topics=["task.created"])
    consumer.run(TaskCreatedEvent, handle)
"""

from __future__ import annotations

from collections.abc import Callable

from confluent_kafka import Consumer, KafkaException
from confluent_kafka.schema_registry.protobuf import ProtobufDeserializer
from confluent_kafka.serialization import MessageField, SerializationContext

from mind.messaging.config import MessagingSettings, load_settings

_DESERIALIZER_CONF = {"use.deprecated.format": False}


class ProtobufConsumer:
    def __init__(
        self,
        group_id: str,
        topics: list[str],
        settings: MessagingSettings | None = None,
        conf_overrides: dict | None = None,
    ):
        """`conf_overrides` replaces librdkafka keys for this consumer only,
        e.g. a longer max.poll.interval.ms for a handler that runs for minutes."""
        self._settings = settings or load_settings()
        conf = self._settings.consumer_conf(group_id) | (conf_overrides or {})
        self._consumer = Consumer(conf)
        self._consumer.subscribe(list(topics))
        self._deserializers: dict[type, ProtobufDeserializer] = {}

    def _deserializer(self, message_type: type) -> ProtobufDeserializer:
        if message_type not in self._deserializers:
            self._deserializers[message_type] = ProtobufDeserializer(
                message_type, _DESERIALIZER_CONF
            )
        return self._deserializers[message_type]

    def run(
        self,
        message_type: type,
        handler: Callable[[object, object], None],
        *,
        max_messages: int | None = None,
        poll_timeout: float = 1.0,
        idle_timeout: float | None = None,
    ) -> int:
        """Poll -> deserialize -> handler -> commit, until max_messages is
        reached, idle_timeout elapses with no message, or the process is
        stopped. Returns the number handled."""
        deserialize = self._deserializer(message_type)
        handled = 0
        idle = 0.0
        try:
            while True:
                msg = self._consumer.poll(poll_timeout)
                if msg is None:
                    idle += poll_timeout
                    if idle_timeout is not None and idle >= idle_timeout:
                        return handled
                    continue
                idle = 0.0
                if msg.error():
                    raise KafkaException(msg.error())

                ctx = SerializationContext(msg.topic(), MessageField.VALUE)
                event = deserialize(msg.value(), ctx)
                handler(event, msg)
                self._consumer.commit(msg)
                handled += 1
                if max_messages is not None and handled >= max_messages:
                    return handled
        finally:
            self._consumer.close()
