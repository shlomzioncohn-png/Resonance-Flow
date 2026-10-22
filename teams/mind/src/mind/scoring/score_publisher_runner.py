"""#357 / #920: run the score publisher inside MIND's API process.

Started from the API's lifespan (`mind/api/main.py`), the way Chain's and
Inbox's Kafka consumers run inside their own services: when the API is
deployed, so is the publisher, with no extra entry in Relay's chart. Three
threads share one ScorePublisher, so its "last published" state covers both
topics: one consumer each for task.created and task.updated, and the rescore
worker that re-scores the tasks #474's propagation asks for.

Only started when Kafka is configured (the chart's config file sets
kafka.bootstrap_servers and kafka.schema_registry_url). Locally and in tests,
with no kafka section, the API starts without it. Only one of the two set is
a broken config (#481), so the ConfigFileError naming the missing key stops
the API's start-up before any thread starts.

The publisher keeps its state in memory, so MIND must run as one replica with
one uvicorn worker: a second copy would split the topics between two states.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from threading import Event
from typing import Any

from google.protobuf.message import Message

from mind.messaging import ProtobufConsumer, ProtobufProducer
from mind.messaging.background import BackgroundThreads, Progress, Target
from mind.messaging.config import MessagingSettings, load_settings
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.kafka_sink import KafkaScoreSink
from mind.scoring.score_publisher import ScorePublisher

logger = logging.getLogger(__name__)

_GROUP_ID = "mind-score-publisher"


@dataclass(frozen=True)
class RunningScorePublisher:
    """The publisher and the threads that feed it, as the API keeps them.

    `publisher` is what #474's propagation calls `rescore` on.
    """

    publisher: ScorePublisher
    threads: BackgroundThreads

    @property
    def healthy(self) -> bool:
        return self.threads.healthy

    @property
    def retrying(self) -> list[str]:
        """Threads failing and retrying: while one is, its scores aren't sent."""
        return self.threads.retrying

    def stop(self) -> None:
        self.threads.stop()


def start_score_publisher(
    settings: MessagingSettings | None = None,
) -> RunningScorePublisher | None:
    """Start the publisher's threads, or return None when Kafka is not configured.

    Raises ConfigFileError when only one of the two kafka keys is set.
    """
    settings = settings or load_settings()
    if not settings.kafka_configured():
        logger.info("score_publisher: Kafka is not configured, not started")
        return None

    publisher = ScorePublisher(KafkaScoreSink(ProtobufProducer(settings)))
    threads = BackgroundThreads(
        [
            (
                "score-publisher-task.created",
                _consume(settings, "task.created", TaskCreatedEvent, publisher.handle_task_created),
            ),
            (
                "score-publisher-task.updated",
                _consume(settings, "task.updated", TaskUpdatedEvent, publisher.handle_task_updated),
            ),
            ("score-publisher-rescore", publisher.run_rescores),
        ]
    )
    threads.start()
    logger.info("score_publisher: started")
    return RunningScorePublisher(publisher=publisher, threads=threads)


# Any: each handler takes its own topic's event type, which run() has
# already deserialized into exactly that type.
def _consume(
    settings: MessagingSettings,
    topic: str,
    message_type: type[Message],
    handle: Callable[[Any], None],
) -> Target:
    def run(stop: Event, progress: Progress) -> None:
        def _handler(event: object, msg: object) -> None:
            # No try/except: a Kafka error must end the loop with the offset
            # uncommitted. BackgroundThreads logs it and runs this again.
            handle(event)
            # Handled (or skipped as one that can never be scored): the thread
            # is moving again.
            progress()

        consumer = ProtobufConsumer(group_id=_GROUP_ID, topics=[topic], settings=settings)
        consumer.run(message_type, _handler, idle_timeout=None, stop=stop)

    return run
