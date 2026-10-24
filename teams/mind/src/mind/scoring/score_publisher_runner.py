"""#357: long-running process that scores Inbox's tasks and publishes
task.score.proposed to Chain. Own process, like index_consumer_runner:
ProtobufConsumer.run() blocks, so one thread per topic.

Both threads share one ScorePublisher, so the "last published" state covers
task.created and task.updated together. A third thread runs the publisher's
rescore worker, which re-scores the tasks #474's propagation asks for.

The model is `HierarchicalRankingModel` (#474), not the baseline directly --
it replaces it here, in production, while the baseline itself stays what
R1's harness scores unchanged for comparison.

Usage:
    make run-score-publisher
    python -m mind.scoring.score_publisher_runner
"""

import logging
import os
import threading
from collections.abc import Callable
from typing import Any

from google.protobuf.message import Message

from mind.config import get_settings
from mind.messaging import ProtobufConsumer, ProtobufProducer
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.ancestor_source import ChainAncestorSource
from mind.scoring.chain_hierarchy_client import ChainHierarchyClient
from mind.scoring.hierarchical_model import HierarchicalRankingModel
from mind.scoring.inbox_task import InboxTask
from mind.scoring.kafka_sink import KafkaScoreSink
from mind.scoring.score_publisher import ScorePublisher
from mind.scoring.segmented_model import SegmentedRankingModel

logger = logging.getLogger(__name__)

_GROUP_ID = "mind-score-publisher"


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


def main() -> None:
    logging.basicConfig(level=get_settings().log_level)
    threading.excepthook = _die_on_thread_crash

    chain_client = ChainHierarchyClient()
    publisher_ref = _PublisherRef()
    baseline = SegmentedRankingModel()
    model = HierarchicalRankingModel(
        ChainAncestorSource(chain_client, baseline, publisher_ref.cached_task),
        baseline=baseline,
    )
    publisher = ScorePublisher(
        KafkaScoreSink(ProtobufProducer()), model=model, children_client=chain_client
    )
    publisher_ref.publisher = publisher

    topics: list[tuple[str, type[Message], _Handler]] = [
        ("task.created", TaskCreatedEvent, publisher.handle_task_created),
        ("task.updated", TaskUpdatedEvent, publisher.handle_task_updated),
    ]
    threads = [
        threading.Thread(target=_run_topic, args=t, name=f"score-publisher-{t[0]}") for t in topics
    ]
    threads.append(threading.Thread(target=publisher.run_rescores, name="score-publisher-rescore"))
    for t in threads:
        t.start()
    for t in threads:
        t.join()


if __name__ == "__main__":
    main()
