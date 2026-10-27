"""Recorded real `task.created` output, through today's code, into the harness (#175).

`eval/real_shaped/` is a scenario written once from a demo run, so it cannot
notice a later change to what real tasks look like. This test can: it takes
the recorded events (#174) and builds each task the way the live path does
now — the current generated proto (`shared/proto/`), then `to_scoring_task`
(#357) — and loads the result into the harness's `Task`. A change on any of
those three that the harness cannot take fails here, in the PR that makes it:

- the proto drops or renames a field the events carry (parsed strictly, so an
  unknown field is an error, not skipped);
- `to_scoring_task` starts producing something `Task` rejects;
- `Task` stops accepting what real tasks look like.

It does not notice a change in what the pipeline *produces* (say, tier no
longer filled): that needs a new demo run, not a recorded one.
"""

import json
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any

import pytest
from google.protobuf.json_format import ParseDict

from mind.evaluation.interfaces import ScoreContext, Task
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.scoring.inbox_task import to_scoring_task
from mind.scoring.segmented_model import SegmentedRankingModel

RUN_DIR = (
    Path(__file__).resolve().parents[1] / "eval" / "real_output" / "2026-10-26_demo-slack-first"
)


def _events() -> list[dict[str, Any]]:
    messages = json.loads((RUN_DIR / "task.created.json").read_text(encoding="utf-8"))
    return [message["value"] for message in messages]


EVENTS = _events()


def _as_harness_task(value: dict[str, Any]) -> Task:
    event = ParseDict(value, TaskCreatedEvent())  # strict: an unknown field fails
    fields = asdict(to_scoring_task(event))
    fields.pop("tenant_id")  # carried for Chain, not a Task field
    return Task.model_validate(fields)


def test_the_recorded_run_has_the_three_demo_tasks():
    assert len(EVENTS) == 3


@pytest.mark.parametrize("value", EVENTS, ids=[v["title"][:30] for v in EVENTS])
def test_a_recorded_task_loads_into_the_harness(value):
    task = _as_harness_task(value)

    assert task.task_id == value["id"]
    assert task.title == value["title"]


def test_the_empty_fields_arrive_empty_not_filled_in():
    # T1 came with no description and no effort estimate; nothing on the way
    # may invent them.
    t1 = next(v for v in EVENTS if "description" not in v)

    task = _as_harness_task(t1)

    assert task.description is None
    assert task.effort_estimate is None
    assert task.tier == "tactical"


@pytest.mark.parametrize("value", EVENTS, ids=[v["title"][:30] for v in EVENTS])
def test_the_harness_task_scores_like_the_live_path(value):
    # Same model, same moment: the harness's copy and the live InboxTask must
    # get the same score, or the harness is grading something else.
    now = datetime.fromisoformat(value["created_at"])
    context = ScoreContext(current_datetime=now)
    model = SegmentedRankingModel()
    live = to_scoring_task(ParseDict(value, TaskCreatedEvent()))

    assert model.score(_as_harness_task(value), context) == model.score(live, context)
