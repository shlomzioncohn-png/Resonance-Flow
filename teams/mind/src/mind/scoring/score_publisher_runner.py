"""#357 / #920: run the score publisher inside MIND's API process.

Started from the API's lifespan (`mind/api/main.py`), the way Chain's and
Inbox's Kafka consumers run inside their own services: when the API is
deployed, so is the publisher, with no extra entry in Relay's chart. Three
threads share one ScorePublisher, so its "last published" state covers both
topics: one consumer each for task.created and task.updated, and the rescore
worker that re-scores the tasks #474's propagation asks for.

The model is `HierarchicalRankingModel` (#474), not the baseline directly --
it replaces it here, in production, while the baseline itself stays what
R1's harness scores unchanged for comparison.

Only started when Kafka is configured (the chart's config file sets
kafka.bootstrap_servers and kafka.schema_registry_url). Locally and in tests,
with no kafka section, the API starts without it. Only one of the two set is
a broken config (#481), so the ConfigFileError naming the missing key stops
the API's start-up before any thread starts.

The publisher keeps its state in memory, so MIND must run as one replica with
one uvicorn worker: a second copy would split the topics between two states.

Every score and every Chain request is recorded with record_call() (#482),
from the outside: the model is wrapped in `RecordedScorer`, and the Chain
client is handed an HTTP client that records its requests. Neither the model
nor the client changes; a score whose Chain lookup failed is recorded as
`degraded` (observability/recording.py).
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from threading import Event
from typing import Any

import httpx
from google.protobuf.message import Message

from mind.messaging import ProtobufConsumer, ProtobufProducer
from mind.messaging.background import BackgroundThreads, Progress, Target
from mind.messaging.config import MessagingSettings, load_settings
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.observability.recording import (
    RecordedScorer,
    RecordingClient,
    name_by_last_path_segment,
)
from mind.observability.tracking import CallKind
from mind.scoring.ancestor_source import ChainAncestorSource
from mind.scoring.chain_hierarchy_client import (
    ChainHierarchyClient,
    ChainHierarchySettings,
    load_chain_hierarchy_settings,
)
from mind.scoring.hierarchical_model import HierarchicalRankingModel
from mind.scoring.inbox_task import InboxTask
from mind.scoring.kafka_sink import KafkaScoreSink
from mind.scoring.score_publisher import ScorePublisher
from mind.scoring.segmented_model import SegmentedRankingModel

logger = logging.getLogger(__name__)

# One consumer group per topic: in a shared group, every retry of one topic's
# consumer (leave, then join again) would rebalance the other topic's too.
# Relay has these names.
_GROUP_IDS = {
    "task.created": "mind-score-publisher-task-created",
    "task.updated": "mind-score-publisher-task-updated",
}

#: The name every score is recorded under (#482).
SCORING_CALL_NAME = "hierarchical-ranking"


def _recorded_chain_client(
    settings: ChainHierarchySettings | None = None,
    transport: httpx.BaseTransport | None = None,
) -> ChainHierarchyClient:
    """The Chain client, over an HTTP client whose every request is recorded
    as `chain-ancestors` / `chain-children` (kind CHAIN).

    The HTTP client is built with the arguments `ChainHierarchyClient` builds
    its own with -- base URL and timeout, httpx's defaults otherwise -- so it
    sends exactly as before. `transport` replaces httpx's own, for tests.
    """
    settings = settings if settings is not None else load_chain_hierarchy_settings()
    http_client = RecordingClient(
        call_name=name_by_last_path_segment("chain"),
        kind=CallKind.CHAIN,
        base_url=settings.base_url,
        timeout=httpx.Timeout(settings.timeout_seconds),
        transport=transport,
    )
    return ChainHierarchyClient(settings, http_client)


class _PublisherRef:
    """Forwards `cached_task` to a `ScorePublisher` this process has not
    built yet. `ChainAncestorSource` needs it at construction time, but the
    publisher is itself built from the model that holds that source --
    `publisher` is filled in right after, once, and every call here happens
    later still, from inside the model's own `score()`.
    """

    def __init__(self) -> None:
        self.publisher: ScorePublisher | None = None

    def cached_task(self, task_id: str) -> InboxTask | None:
        assert self.publisher is not None
        return self.publisher.cached_task(task_id)


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

    chain_client = _recorded_chain_client()
    publisher_ref = _PublisherRef()
    baseline = SegmentedRankingModel()
    model = RecordedScorer(
        HierarchicalRankingModel(
            ChainAncestorSource(chain_client, baseline, publisher_ref.cached_task),
            baseline=baseline,
        ),
        SCORING_CALL_NAME,
    )
    publisher = ScorePublisher(
        KafkaScoreSink(ProtobufProducer(settings)), model=model, children_client=chain_client
    )
    publisher_ref.publisher = publisher

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

        consumer = ProtobufConsumer(group_id=_GROUP_IDS[topic], topics=[topic], settings=settings)
        consumer.run(message_type, _handler, idle_timeout=None, stop=stop)

    return run
