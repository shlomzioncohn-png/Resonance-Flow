#!/usr/bin/env python3
"""Round trip both Kafka flows against a real broker + schema registry:
consuming task.created from Inbox, producing task.dependencies.detected to
Chain. Neither is deployed, so this script plays both sides.

Usage:
    pip install -e ".[dev]"
    python scripts/round-trip.py --bootstrap-servers localhost:9092 \
        --schema-registry-url http://localhost:8081/apis/ccompat/v6
"""

import argparse
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from mind.messaging import ProtobufConsumer, ProtobufProducer
from mind.messaging.mind_task_created_pb2 import (
    EXTRACTION_PROVENANCE_MANUAL,
    SOURCE_TYPE_SLACK,
    TASK_STATUS_NEW,
    Task,
)
from mind.messaging.mind_task_dependencies_pb2 import TaskDependenciesDetected


def task_created_round_trip() -> bool:
    print("\n--- Flow 1: task.created (Inbox -> MIND) ---")
    now = datetime.now(timezone.utc)
    task = Task(
        task_id="22222222-2222-2222-2222-222222222222",
        organization_id="11111111-1111-1111-1111-111111111111",
        title="Send the revised pricing deck to Acme",
        source_type=SOURCE_TYPE_SLACK,
        status=TASK_STATUS_NEW,
        is_available=True,
        extraction_provenance=EXTRACTION_PROVENANCE_MANUAL,
        created_at=now,
        updated_at=now,
    )
    with ProtobufProducer() as producer:
        producer.publish("task.created", task, key=task.organization_id)
    print(f"published: {task.title!r} -> task.created")

    received = {}

    def handle(event, msg):
        received["title"] = event.title
        received["task_id"] = event.task_id
        print(f"consumed: task.created[{msg.partition()}]@{msg.offset()} "
              f"title={event.title!r} task_id={event.task_id!r}")

    n = ProtobufConsumer(group_id="round-trip-task-created", topics=["task.created"]).run(
        Task, handle, max_messages=1, idle_timeout=30
    )
    ok = n == 1 and received.get("title") == task.title and received.get("task_id") == task.task_id
    print("PASS" if ok else "FAIL - did not receive the same event back")
    return ok


def task_dependencies_round_trip() -> bool:
    print("\n--- Flow 2: task.dependencies.detected (MIND -> Chain) ---")
    dep = TaskDependenciesDetected(
        tenant_id="11111111-1111-1111-1111-111111111111",
        task_id="22222222-2222-2222-2222-222222222222",
        depends_on_task_ids=["33333333-3333-3333-3333-333333333333"],
        estimated_duration_minutes=30,
        created_at=datetime.now(timezone.utc),
    )
    with ProtobufProducer() as producer:
        producer.publish("task.dependencies.detected", dep, key=dep.tenant_id)
    print(f"published: task_id={dep.task_id!r} depends_on={list(dep.depends_on_task_ids)}")

    received = {}

    def handle(event, msg):
        received["task_id"] = event.task_id
        received["depends_on"] = list(event.depends_on_task_ids)
        print(f"consumed: task.dependencies.detected[{msg.partition()}]@{msg.offset()} "
              f"task_id={event.task_id!r} depends_on={list(event.depends_on_task_ids)}")

    n = ProtobufConsumer(
        group_id="round-trip-task-dependencies", topics=["task.dependencies.detected"]
    ).run(TaskDependenciesDetected, handle, max_messages=1, idle_timeout=30)
    ok = (
        n == 1
        and received.get("task_id") == dep.task_id
        and received.get("depends_on") == list(dep.depends_on_task_ids)
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

    print("\n--- Result ---")
    print(f"task.created:              {'PASS' if ok1 else 'FAIL'}")
    print(f"task.dependencies.detected: {'PASS' if ok2 else 'FAIL'}")
    return 0 if ok1 and ok2 else 1


if __name__ == "__main__":
    sys.exit(main())
