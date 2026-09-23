"""Kafka producer/consumer for MIND, RELAY<->MIND integration.

Handles the Protobuf + schema-registry serialization (the Confluent wire
format), the connection wiring, and the produce/consume loops. MIND fills
in only the domain parts:

  - producing: build the protobuf message from the extraction pipeline,
    then `producer.publish(topic, message)`
  - consuming: write a `handler(message, meta)` and pass it to
    `consumer.run(MessageType, handler)`

See README.md.
"""

from mind.messaging.config import MessagingSettings, load_settings
from mind.messaging.consumer import ProtobufConsumer
from mind.messaging.producer import ProtobufProducer

__all__ = [
    "MessagingSettings",
    "ProtobufConsumer",
    "ProtobufProducer",
    "load_settings",
]
