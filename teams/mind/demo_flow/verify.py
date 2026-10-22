"""#359: what a demo run produced, checked against expected.json.

Reads every message on the four topics the run produces into, from the
start: each run has a fresh broker (stack/docker-compose.yml), so what is
there is this run's.

    task.creation.requested     Mind -> Inbox   one per task Mind decided to create
    task.created                Inbox -> Mind   Inbox's task, correlation_id header
    task.score.proposed         Mind -> Chain   the score of each created task
    task.dependencies.proposed  Mind -> Chain   the dependency found between them

Each request is traced back to its source item by correlation_id: Mind
derives it from Bridge's correlation_id and the task's number
(`correlation_id_for`, `task_index_for`), and the run manifest has Bridge's. Each request is
matched to T1-T3 by its title, the one field the model writes that names the
work; a request no title matches is an unexpected task.

A merged duplicate sends nothing, so it is proven from the landed runner's
log instead (`_duplicate_check`). The verdict is one line per acceptance
criterion of #359, PASS or FAIL, with what was seen. `evaluate` is pure, so tests feed it messages without Kafka.

Usage (run.py calls it at the end of a run; on its own, against a stack
that is still up):

    python -m demo_flow.verify runs/<run-id>.json
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Any
from uuid import UUID

from demo_flow import common
from mind.ingestion.creation_pipeline import TASK_SLOTS_PER_ITEM, task_index_for
from mind.task_creation.correlation import correlation_id_for
from mind.task_creation.reply import CORRELATION_HEADER

logger = logging.getLogger("demo_flow.verify")

REQUESTED = "task.creation.requested"
CREATED = "task.created"
SCORED = "task.score.proposed"
DEPENDENCIES = "task.dependencies.proposed"
TOPICS = (REQUESTED, CREATED, SCORED, DEPENDENCIES)

#: Words of each expected task's title that a correct title for it carries
#: one of. Lower case, matched as substrings of the title.
TITLE_WORDS: dict[str, tuple[str, ...]] = {
    "T1": ("quote", "pricing", "proposal"),
    "T2": ("cost sheet",),
    "T3": ("registration", "supplier form", "form"),
}

_WEEKDAYS = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")


@dataclass
class Observed:
    """The messages of one run, by topic, as plain dicts (MessageToDict)."""

    requested: list[dict[str, Any]] = field(default_factory=list)
    #: (correlation_id header or None, the event)
    created: list[tuple[str | None, dict[str, Any]]] = field(default_factory=list)
    scored: list[dict[str, Any]] = field(default_factory=list)
    dependencies: list[dict[str, Any]] = field(default_factory=list)

    def count(self) -> int:
        return len(self.requested) + len(self.created) + len(self.scored) + len(self.dependencies)


@dataclass(frozen=True)
class Check:
    criterion: str
    passed: bool
    detail: str


@dataclass(frozen=True)
class Task:
    """One request Mind sent, traced to its source item and expected task."""

    correlation_id: str
    source: str  # meeting / slack / email, or "?" when no item derives it
    expected_id: str | None  # T1..T3, or None for an unexpected task
    request: dict[str, Any]
    created: dict[str, Any] | None  # Inbox's task.created, if it came


def match_expected(title: str) -> str | None:
    lowered = title.lower()
    for task_id, words in TITLE_WORDS.items():
        if any(word in lowered for word in words):
            return task_id
    return None


def _sources_by_correlation(manifest: dict[str, Any]) -> dict[str, str]:
    """Every request correlation_id Mind could derive from this run's items -> item name.

    Since #690 one text yields several tasks: the pipeline numbers each by its
    file's position in the event and its own in the list (`task_index_for`).
    """

    sources = {}
    for item in manifest["items"]:
        bridge_id = UUID(item["correlation_id"])
        for file_position in range(len(item["event"].get("objects", []))):
            for task_position in range(TASK_SLOTS_PER_ITEM):
                index = task_index_for(file_position, task_position)
                sources[str(correlation_id_for(bridge_id, index))] = item["name"]
    return sources


def trace(observed: Observed, manifest: dict[str, Any]) -> list[Task]:
    sources = _sources_by_correlation(manifest)
    created_by_correlation = {header: event for header, event in observed.created if header}
    tasks = []
    for request in observed.requested:
        correlation_id = request.get("correlationId", "")
        tasks.append(
            Task(
                correlation_id=correlation_id,
                source=sources.get(correlation_id, "?"),
                expected_id=match_expected(request.get("title", "")),
                request=request,
                created=created_by_correlation.get(correlation_id),
            )
        )
    return tasks


def _due_date(scenario_monday: date, weekday: str) -> date:
    return scenario_monday + timedelta(days=_WEEKDAYS.index(weekday))


def _describe(task: Task) -> str:
    return (
        f"{task.expected_id or 'unexpected'} {task.request.get('title', '')!r} (from {task.source})"
    )


def _field_checks(task: Task, spec: dict[str, Any], scenario_monday: date) -> list[str]:
    """What is missing or wrong on one created task, against its expected entry."""

    request = task.request
    problems = []
    for name in ("tier", "urgency", "roleContext"):
        if name not in request:
            problems.append(f"no {name}")
    if spec.get("suggested_owner") and "suggestedOwnerUserId" not in request:
        problems.append(f"no suggested owner (expected {spec['suggested_owner']})")
    minutes = spec.get("effort_estimate_minutes")
    if minutes is not None:
        got = request.get("effortEstimateMinutes")
        if got is None:
            problems.append(f"no effort estimate (expected ~{minutes} min)")
        elif not 180 <= int(got) <= 300:
            problems.append(f"effort {got} min (expected 180-300)")
    due = _due_date(scenario_monday, spec["deadline_weekday"])
    deadline = request.get("deadline")
    if deadline is None:
        problems.append(f"no deadline (expected {spec['deadline_weekday']} {due})")
    elif not str(deadline).startswith(due.isoformat()):
        problems.append(f"deadline {deadline} (expected {spec['deadline_weekday']} {due})")
    return problems


def _why_no_decision(item: dict[str, Any], landed_log: str) -> str:
    """Why an item's mention never reached the duplicate check, as far as the log tells."""

    keys = [ref["object_key"] for ref in item["event"].get("objects", [])]
    for line in landed_log.splitlines():
        if "extraction: giving up on" in line and any(key in line for key in keys):
            return "its extraction failed: " + line.split(": ", 2)[-1][:160]
    return "no duplicate decision for it in the landed runner's log"


def _duplicate_check(
    duplicate: dict[str, Any],
    mentions: list[Task],
    items: dict[str, dict[str, Any]],
    landed_log: str,
) -> Check:
    """One task from several mentions, proven by the duplicate check itself.

    One request on the topic is not enough: a mention whose extraction failed
    sends nothing either, and would pass for a merged duplicate. A merged one
    leaves only the landed runner's INFO line, "creation: <item> task N not
    sent, ... is a duplicate of task <id>" (creation_pipeline.py), so every
    mention but the one sent must have that line, naming the task that was.
    """

    criterion = (
        f"{duplicate['task']} is one task, not {len(duplicate['sources'])} "
        f"({' + '.join(duplicate['sources'])})"
    )
    if len(mentions) != 1:
        found = "; ".join(_describe(t) for t in mentions) or "no request for it at all"
        return Check(criterion, False, f"{len(mentions)} request(s): {found}")
    sent = mentions[0]
    if sent.created is None:
        return Check(
            criterion, False, f"the request ({_describe(sent)}) was never created by Inbox"
        )

    merged, unproven = [], []
    for source in duplicate["sources"]:
        if source == sent.source:
            continue
        item = items[source]
        line = re.compile(
            rf"creation: {re.escape(item['correlation_id'])} task \d+ not sent, .* "
            rf"is a duplicate of task {re.escape(sent.created['id'])} \(similarity ([0-9.]+)\)"
        ).search(landed_log)
        if line:
            merged.append(f"{source} matched it (similarity {line.group(1)})")
        else:
            unproven.append(f"{source}: {_why_no_decision(item, landed_log)}")
    detail = f"sent from {sent.source}; " + "; ".join(merged + unproven)
    if unproven:
        detail = "not proven, " + detail
    return Check(criterion, not unproven, detail)


def evaluate(
    observed: Observed,
    manifest: dict[str, Any],
    expected: dict[str, Any],
    landed_log: str = "",
) -> list[Check]:
    """One Check per acceptance criterion of #359 that a run can show.

    `landed_log` is the landed runner's output (runs/<run-id>/mind-landed.log):
    a merged duplicate shows only there, not on any topic.
    """

    scenario_monday = date.fromisoformat(manifest["scenario_monday"])
    tasks = trace(observed, manifest)
    by_expected: dict[str, list[Task]] = {}
    for task in tasks:
        by_expected.setdefault(task.expected_id or "unexpected", []).append(task)
    expected_ids = [spec["id"] for spec in expected["tasks"]]
    created = [task for task in tasks if task.created is not None]

    checks = []

    missing = [task_id for task_id in expected_ids if task_id not in by_expected]
    extra = by_expected.get("unexpected", [])
    checks.append(
        Check(
            "three tasks come out, the expected ones",
            not missing and not extra and len(tasks) == len(expected_ids),
            f"{len(tasks)} requested, {len(created)} created by Inbox"
            + (f"; missing {', '.join(missing)}" if missing else "")
            + (f"; unexpected: {'; '.join(_describe(t) for t in extra)}" if extra else ""),
        )
    )

    not_created = [task for task in tasks if task.created is None]
    checks.append(
        Check(
            "Inbox created every task Mind requested",
            bool(tasks) and not not_created,
            "none requested"
            if not tasks
            else (
                "all created"
                if not not_created
                else "no task.created for: " + "; ".join(_describe(t) for t in not_created)
            ),
        )
    )

    items = {item["name"]: item for item in manifest["items"]}
    for duplicate in expected["duplicates"]:
        checks.append(
            _duplicate_check(duplicate, by_expected.get(duplicate["task"], []), items, landed_log)
        )

    task_ids = {
        expected_id: [t.created["id"] for t in group if t.created is not None]
        for expected_id, group in by_expected.items()
    }
    for dependency in expected["dependencies"]:
        dependents = set(task_ids.get(dependency["task"], []))
        prerequisites = set(task_ids.get(dependency["depends_on"], []))
        found = any(
            event.get("taskId") in dependents
            and any(
                d.get("dependsOnTaskId") in prerequisites for d in event.get("dependencies", [])
            )
            for event in observed.dependencies
        )
        checks.append(
            Check(
                f"{dependency['task']} depends on {dependency['depends_on']}, "
                f"on {dependency['published_to']}",
                found,
                "published"
                if found
                else f"{len(observed.dependencies)} message(s) on {DEPENDENCIES}, none for this pair",
            )
        )

    specs = {spec["id"]: spec for spec in expected["tasks"]}
    field_problems = []
    for task in tasks:
        if task.expected_id is None:
            continue
        problems = _field_checks(task, specs[task.expected_id], scenario_monday)
        if problems:
            field_problems.append(f"{task.expected_id}: {', '.join(problems)}")
    checks.append(
        Check(
            "tasks carry tier, urgency, role_context, owner, effort and deadline where expected",
            bool(tasks) and not field_problems,
            "; ".join(field_problems) if field_problems else ("all set" if tasks else "no tasks"),
        )
    )

    scored_ids = {event.get("taskId") for event in observed.scored}
    unscored = [t for t in created if t.created is not None and t.created["id"] not in scored_ids]
    checks.append(
        Check(
            "every created task gets a score on task.score.proposed",
            bool(created) and not unscored,
            "no task created"
            if not created
            else (
                f"{len(created)} scored"
                if not unscored
                else "no score for: " + "; ".join(_describe(t) for t in unscored)
            ),
        )
    )
    return checks


def report(checks: list[Check], tasks: list[Task]) -> str:
    lines = ["", "demo flow result", "----------------"]
    for task in tasks:
        request = task.request
        lines.append(
            f"  {_describe(task)}: tier={request.get('tier', '-')} urgency={request.get('urgency', '-')} "
            f"role={request.get('roleContext', '-')} effort={request.get('effortEstimateMinutes', '-')} "
            f"deadline={request.get('deadline', '-')} "
            f"inbox_id={task.created['id'] if task.created else '-'}"
        )
    lines.append("")
    for check in checks:
        lines.append(f"  {'PASS' if check.passed else 'FAIL'}  {check.criterion}: {check.detail}")
    passed = sum(check.passed for check in checks)
    lines.append(f"\n  {passed}/{len(checks)} criteria pass")
    return "\n".join(lines)


def collect(
    bootstrap_servers: str,
    done: Callable[[Observed], bool],
    timeout_s: float,
) -> Observed:
    """Read the four topics from the start until `done(observed)` or the timeout."""

    from confluent_kafka import Consumer
    from confluent_kafka.schema_registry.protobuf import ProtobufDeserializer
    from confluent_kafka.serialization import MessageField, SerializationContext
    from google.protobuf.json_format import MessageToDict

    from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
    from mind.messaging.task_creation_requested_event_pb2 import TaskCreationRequestedEvent
    from mind.messaging.task_dependencies_proposed_event_pb2 import DependencyProposedEvent
    from mind.messaging.task_score_proposed_event_pb2 import TaskScoreProposedEvent

    # The registry is not asked: deserializing needs only the message type.
    conf = {"use.deprecated.format": False}
    deserializers = {
        REQUESTED: ProtobufDeserializer(TaskCreationRequestedEvent, conf),
        CREATED: ProtobufDeserializer(TaskCreatedEvent, conf),
        SCORED: ProtobufDeserializer(TaskScoreProposedEvent, conf),
        DEPENDENCIES: ProtobufDeserializer(DependencyProposedEvent, conf),
    }
    consumer = Consumer(
        {
            "bootstrap.servers": bootstrap_servers,
            # A new group every time: read from the start, commit nothing.
            "group.id": f"demo-verify-{time.time_ns()}",
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )
    consumer.subscribe(list(TOPICS))
    observed = Observed()
    deadline = time.monotonic() + timeout_s
    try:
        while time.monotonic() < deadline and not done(observed):
            msg = consumer.poll(1.0)
            if msg is None or msg.error():
                continue
            topic = msg.topic()
            event = deserializers[topic](
                msg.value(), SerializationContext(topic, MessageField.VALUE)
            )
            as_dict = MessageToDict(event)
            if topic == REQUESTED:
                observed.requested.append(as_dict)
            elif topic == CREATED:
                header = dict(msg.headers() or []).get(CORRELATION_HEADER)
                observed.created.append((header.decode() if header else None, as_dict))
            elif topic == SCORED:
                observed.scored.append(as_dict)
            else:
                observed.dependencies.append(as_dict)
    finally:
        consumer.close()
    return observed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("manifest", type=Path, help="runs/<run-id>.json, written by the feed")
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--timeout", type=float, default=15.0, help="seconds to read for")
    parser.add_argument(
        "--landed-log",
        type=Path,
        help="the landed runner's log (default: runs/<run-id>/mind-landed.log next to the manifest)",
    )
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    expected = common.read_json(common.EXPECTED)
    observed = collect(args.bootstrap_servers, lambda _: False, args.timeout)
    landed_log_path = args.landed_log or args.manifest.with_suffix("") / "mind-landed.log"
    landed_log = landed_log_path.read_text(encoding="utf-8") if landed_log_path.is_file() else ""
    checks = evaluate(observed, manifest, expected, landed_log)
    print(report(checks, trace(observed, manifest)))
    return 0 if all(check.passed for check in checks) else 1


if __name__ == "__main__":
    sys.exit(main())
