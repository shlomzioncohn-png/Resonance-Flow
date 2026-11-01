#!/usr/bin/env python3
"""Round trip MIND's Kafka flows against a real broker + schema registry:
consuming task.created from Inbox, producing task.dependencies.proposed and
task.score.proposed to Chain. None is deployed, so this script plays both sides.

Usage:
    pip install -e ".[dev]"
    python scripts/round-trip.py --bootstrap-servers localhost:9092 \
        --schema-registry-url http://localhost:8081/apis/ccompat/v6
"""

import argparse
import os
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path

from mind.messaging import ProtobufConsumer, ProtobufProducer
from mind.messaging.task_created_event_pb2 import (
    EXTRACTION_PROVENANCE_MANUAL,
    SOURCE_TYPE_SLACK,
    TASK_STATUS_NEW,
    TASK_TIER_TACTICAL,
    TaskCreatedEvent,
)
from mind.messaging.task_dependencies_proposed_event_pb2 import (
    Dependency,
    DependencyProposedEvent,
)
from mind.messaging.task_score_proposed_event_pb2 import TaskScoreProposedEvent


def consume_own_event(group_id, topic, message_type, event_id, handle) -> bool:
    found = []

    def handle_own(event, msg):
        if event.event_id == event_id:
            handle(event, msg)
            found.append(event)
        else:
            print(f"skipped: {topic}[{msg.partition()}]@{msg.offset()} event_id={event.event_id!r}")

    while not found:
        n = ProtobufConsumer(group_id=group_id, topics=[topic]).run(
            message_type, handle_own, max_messages=1, idle_timeout=30
        )
        if n == 0:
            return False
    return True


def task_created_round_trip() -> bool:
    print("\n--- Flow 1: task.created (Inbox -> MIND) ---")
    task = TaskCreatedEvent(
        id="22222222-2222-2222-2222-222222222222",
        tenant_id="11111111-1111-1111-1111-111111111111",
        title="Send the revised pricing deck to Acme",
        source_type=SOURCE_TYPE_SLACK,
        source_ref="slack://C123/p456",
        status=TASK_STATUS_NEW,
        extraction_provenance=EXTRACTION_PROVENANCE_MANUAL,
        created_at=datetime.now(UTC),
    )
    with ProtobufProducer() as producer:
        producer.publish("task.created", task, key=task.id)
    print(f"published: {task.title!r} -> task.created")

    received = {}

    def handle(event, msg):
        received["title"] = event.title
        received["id"] = event.id
        print(f"consumed: task.created[{msg.partition()}]@{msg.offset()} "
              f"title={event.title!r} id={event.id!r}")

    n = ProtobufConsumer(group_id="round-trip-task-created", topics=["task.created"]).run(
        TaskCreatedEvent, handle, max_messages=1, idle_timeout=30
    )
    ok = n == 1 and received.get("title") == task.title and received.get("id") == task.id
    print("PASS" if ok else "FAIL - did not receive the same event back")
    return ok


def task_dependencies_round_trip() -> bool:
    print("\n--- Flow 2: task.dependencies.proposed (MIND -> Chain) ---")
    dep = DependencyProposedEvent(
        event_id="44444444-4444-4444-4444-444444444444",
        task_id="22222222-2222-2222-2222-222222222222",
        dependencies=[
            Dependency(depends_on_task_id="33333333-3333-3333-3333-333333333333", confidence=0.75)
        ],
    )
    with ProtobufProducer() as producer:
        producer.publish("task.dependencies.proposed", dep, key=dep.task_id)
    edges = [(d.depends_on_task_id, d.confidence) for d in dep.dependencies]
    print(f"published: task_id={dep.task_id!r} dependencies={edges}")

    received = {}

    def handle(event, msg):
        received["task_id"] = event.task_id
        received["edges"] = [(d.depends_on_task_id, d.confidence) for d in event.dependencies]
        print(f"consumed: task.dependencies.proposed[{msg.partition()}]@{msg.offset()} "
              f"task_id={event.task_id!r} dependencies={received['edges']}")

    ok = (
        consume_own_event(
            "round-trip-task-dependencies",
            "task.dependencies.proposed",
            DependencyProposedEvent,
            dep.event_id,
            handle,
        )
        and received.get("task_id") == dep.task_id
        and received.get("edges") == edges
    )
    print("PASS" if ok else "FAIL - did not receive the same event back")
    return ok


def task_score_round_trip() -> bool:
    print("\n--- Flow 3: task.score.proposed (MIND -> Chain) ---")
    score = TaskScoreProposedEvent(
        event_id="55555555-5555-5555-5555-555555555555",
        task_id="22222222-2222-2222-2222-222222222222",
        tier=TASK_TIER_TACTICAL,
        mind_score=0.5,
        confidence=0.75,
        tenant_id="11111111-1111-1111-1111-111111111111",
        scored_at=datetime.now(UTC),
        factors={"deadline": 0.3, "urgency": 0.2},
    )
    with ProtobufProducer() as producer:
        producer.publish("task.score.proposed", score, key=score.task_id)
    print(f"published: task_id={score.task_id!r} mind_score={score.mind_score}")

    received = {}

    def handle(event, msg):
        received["task_id"] = event.task_id
        received["mind_score"] = event.mind_score
        received["tenant_id"] = event.tenant_id
        received["scored_at"] = event.scored_at.ToDatetime(UTC)
        received["factors"] = dict(event.factors)
        print(f"consumed: task.score.proposed[{msg.partition()}]@{msg.offset()} "
              f"task_id={event.task_id!r} mind_score={event.mind_score}")

    ok = (
        consume_own_event(
            "round-trip-task-score",
            "task.score.proposed",
            TaskScoreProposedEvent,
            score.event_id,
            handle,
        )
        and received.get("task_id") == score.task_id
        and received.get("mind_score") == score.mind_score
        and received.get("tenant_id") == score.tenant_id
        and received.get("scored_at") == score.scored_at.ToDatetime(UTC)
        and received.get("factors") == dict(score.factors)
    )
    print("PASS" if ok else "FAIL - did not receive the same event back")
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--schema-registry-url", default="http://localhost:8081/apis/ccompat/v6")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        cfg = Path(tmp) / "config.yaml"
        cfg.write_text(
            "kafka:\n"
            f"  bootstrap_servers: {args.bootstrap_servers}\n"
            f"  schema_registry_url: {args.schema_registry_url}\n"
        )
        os.environ["CONFIG_FILE_PATH"] = str(cfg)

        ok1 = task_created_round_trip()
        ok2 = task_dependencies_round_trip()
        ok3 = task_score_round_trip()

    print("\n--- Result ---")
    print(f"task.created:               {'PASS' if ok1 else 'FAIL'}")
    print(f"task.dependencies.proposed: {'PASS' if ok2 else 'FAIL'}")
    print(f"task.score.proposed:        {'PASS' if ok3 else 'FAIL'}")
    return 0 if ok1 and ok2 and ok3 else 1


if __name__ == "__main__":
    sys.exit(main())
