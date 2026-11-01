#!/usr/bin/env python3
"""#174: real `task.created` events through the ranking harness, unchanged.

Diagnostic only: it records where the harness's scenario `Task`
(`evaluation/interfaces.py`) and real pipeline output disagree, and fixes
nothing (that is #175). Three passes over the same events:

1. **As is.** Each event's `value` straight into `Task.model_validate`, the
   check the harness runs when it loads a scenario. Every error is printed
   by field.
2. **Names mapped.** The event through `to_scoring_task` (#357), the mapping
   real tasks already go through (`id` -> `task_id`, the effort name, the
   proto tier -> lowercase, `blocked=False`), then into `Task`, to show what
   is left behind the renames.
3. **The live path.** `InboxTask` -> `SegmentedRankingModel`, scored at the
   event's own `scored_at`, against the `mind_score` the run published,
   which is the base score. Equal means the live path and this replay agree.
   Also runs `LongestDescriptionModel`, the length-bias probe.

Which code it runs decides what it prints: on the commit #174 recorded
(17cd38ee) pass 2 rejects every UUID and the probe crashes on T1; with #175's
fixes pass 2 accepts all three and the probe does not crash.

Input is two topic dumps from a `demo_flow` run, one JSON list of messages
each, the event in every message's `value` with proto field names.

Usage:
    python scripts/diagnose_real_tasks.py                  # the recorded run
    python scripts/diagnose_real_tasks.py --run-dir DIR    # another dump
"""

import argparse
import json
import sys
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any

from google.protobuf.json_format import ParseDict
from pydantic import ValidationError

from mind.evaluation.baseline_models import LongestDescriptionModel
from mind.evaluation.interfaces import ScoreContext, Task
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.scoring.inbox_task import InboxTask, to_scoring_task
from mind.scoring.segmented_model import SegmentedRankingModel

RECORDED_RUN = (
    Path(__file__).resolve().parent.parent / "eval" / "real_output" / "2026-10-26_demo-slack-first"
)


def _load(path: Path) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = json.loads(path.read_text(encoding="utf-8"))
    return messages


def _label(value: dict[str, Any]) -> str:
    return str(value["title"])[:50]


def _inbox_task(value: dict[str, Any]) -> InboxTask:
    """The event as the live path reads it: proto, then `to_scoring_task`."""
    return to_scoring_task(ParseDict(value, TaskCreatedEvent(), ignore_unknown_fields=True))


def as_is(created: list[dict[str, Any]]) -> None:
    print("=== 1. as is: each task.created value into the harness Task ===")
    for message in created:
        value = message["value"]
        try:
            Task.model_validate(value)
            print(f"{_label(value):50} accepted")
        except ValidationError as e:
            print(f"{_label(value):50} rejected, {e.error_count()} errors")
            for error in e.errors():
                field = ".".join(map(str, error["loc"])) or "-"
                print(f"    {field:26} {error['type']:22} {str(error.get('input'))[:40]!r}")


def names_mapped(created: list[dict[str, Any]]) -> None:
    print("\n=== 2. names mapped: what is left after the obvious renames ===")
    for message in created:
        value = message["value"]
        mapped = asdict(_inbox_task(value))
        mapped.pop("tenant_id")  # carried for Chain, not a Task field
        try:
            Task.model_validate(mapped)
            print(f"{_label(value):50} accepted")
        except ValidationError as e:
            left = "; ".join(
                f"{'.'.join(map(str, error['loc']))}={error['type']}" for error in e.errors()
            )
            print(f"{_label(value):50} rejected: {left}")


def live_path(created: list[dict[str, Any]], scored: list[dict[str, Any]]) -> None:
    print("\n=== 3. live path: InboxTask -> SegmentedRankingModel vs the published score ===")
    published = {message["value"]["task_id"]: message["value"] for message in scored}
    model = SegmentedRankingModel()
    for message in created:
        value = message["value"]
        task = _inbox_task(value)
        score_event = published.get(value["id"])
        if score_event is None:
            print(f"{_label(value):50} no task.score.proposed for it")
            continue
        now = datetime.fromisoformat(score_event["scored_at"])
        context = ScoreContext(current_datetime=now)
        result = model.score(task, context)
        base = result.factors.base_score if result.factors else None
        if base is None:
            print(f"{_label(value):50} no base score in the result; cannot compare")
            continue
        same = abs(base - score_event["mind_score"]) < 1e-6
        print(
            f"{_label(value):50} base {base:.6f}  published {score_event['mind_score']:.6f}  "
            f"{'same' if same else 'DIFFERENT'}"
        )
        try:
            # The probe reads a scenario Task; InboxTask has the same fields.
            LongestDescriptionModel().score(task, context)  # type: ignore[arg-type]
        except TypeError as e:
            print(f"    LongestDescriptionModel: {type(e).__name__}: {e}")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--run-dir",
        type=Path,
        default=RECORDED_RUN,
        help="folder holding task.created.json and task.score.proposed.json "
        "(default: the recorded 2026-10-26 demo run)",
    )
    args = parser.parse_args()
    created = _load(args.run_dir / "task.created.json")
    scored = _load(args.run_dir / "task.score.proposed.json")
    as_is(created)
    names_mapped(created)
    live_path(created, scored)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
