"""#357: long-running process that scores Inbox's tasks and publishes
task.score.proposed to Chain. Own process, like index_consumer_runner:
ProtobufConsumer.run() blocks, so one thread per topic.

Both threads share one ScorePublisher, so the "last published" state covers
task.created and task.updated together.

Not runnable yet: `_kafka_sink` waits for the task.score.proposed proto
(#542, and Chain's answer on the final fields).

Usage:
    python -m mind.scoring.score_publisher_runner
"""

import logging
import os
import threading
from collections.abc import Callable
from typing import Any

from google.protobuf.message import Message

from mind.config import get_settings
from mind.messaging import ProtobufConsumer
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.score_publisher import ScorePublisher, ScoreSink

logger = logging.getLogger(__name__)

_GROUP_ID = "mind-score-publisher"


def _kafka_sink() -> ScoreSink:
    """Converts the payload to the task.score.proposed proto message and
    publishes it with key = task_id. Written once that proto exists."""
    raise NotImplementedError("needs the task.score.proposed proto (#542)")


# Any: each handler takes its own topic's event type, which run() has
# already deserialized into exactly that type.
_Handler = Callable[[Any], None]


def _run_topic(topic: str, message_type: type[Message], handle: _Handler) -> None:
    def _handler(event: object, msg: object) -> None:
        # No try/except: must propagate so the offset stays uncommitted.
        handle(event)

    consumer = ProtobufConsumer(group_id=_GROUP_ID, topics=[topic])
    consumer.run(message_type, _handler, idle_timeout=None)


def _die_on_thread_crash(args: threading.ExceptHookArgs) -> None:
    # A dead topic-thread takes the whole process down, not just itself.
    thread_name = args.thread.name if args.thread is not None else "<unknown>"
    logger.critical(
        "score_publisher: thread %s died, exiting process", thread_name, exc_info=args.exc_value
    )
    os._exit(1)


def main() -> None:
    logging.basicConfig(level=get_settings().log_level)
    threading.excepthook = _die_on_thread_crash
    publisher = ScorePublisher(_kafka_sink())

    topics: list[tuple[str, type[Message], _Handler]] = [
        ("task.created", TaskCreatedEvent, publisher.handle_task_created),
        ("task.updated", TaskUpdatedEvent, publisher.handle_task_updated),
    ]
    threads = [
        threading.Thread(target=_run_topic, args=t, name=f"score-publisher-{t[0]}") for t in topics
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


if __name__ == "__main__":
    main()
