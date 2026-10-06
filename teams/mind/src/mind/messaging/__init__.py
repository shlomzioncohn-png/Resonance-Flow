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

import os
import sys

# protoc's generated Python code imports a cross-referenced proto's pb2
# module by bare name (e.g. task_updated_event_pb2.py does
# `import task_created_event_pb2`, not a package-qualified import) --
# it mirrors the literal `import "task-created-event.proto";` path in the
# .proto file, with no package prefix. That only resolves if this
# directory is on sys.path directly. Never came up for the mind-task-*
# drafts (they don't cross-reference each other); Inbox's real
# task-{created,updated,completed}-event.proto do (#470). This runs before
# any submodule of this package, including a direct
# `from mind.messaging.task_updated_event_pb2 import ...` elsewhere, since
# Python always executes a package's __init__.py before its submodules.
_MESSAGING_DIR = os.path.dirname(__file__)
if _MESSAGING_DIR not in sys.path:
    sys.path.insert(0, _MESSAGING_DIR)

from mind.messaging.config import MessagingSettings, load_settings
from mind.messaging.consumer import ProtobufConsumer
from mind.messaging.producer import ProtobufProducer

__all__ = [
    "MessagingSettings",
    "ProtobufConsumer",
    "ProtobufProducer",
    "load_settings",
]
