"""Acceptance tests for extracting every task a text describes (#690)."""

import json
import logging
from uuid import uuid4

import pytest

from mind.contracts.base_enums import SourceType
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.config import ExtractionConfig
from mind.extraction.extractor import (
    BasicExtractor,
    ExtractedTask,
    ExtractionFailure,
    ExtractionSuccess,
)
from mind.extraction.models import MAX_TASKS_PER_TEXT
from tests.extraction.fakes import ScriptedProvider

MEETING = (
    "Project Manager: Dana, please send the minutes to the client, thread MSG-4471. "
    "Industrial Designer: I will book the lab for the prototype test."
)

MINUTES = {
    "title": "Send the minutes to the client",
    "source_ref": "MSG-4471",
    "has_dependencies": False,
}
LAB = {
    "title": "Book the lab for the prototype test",
    "source_ref": None,
    "has_dependencies": False,
}


def answer(*tasks: object) -> str:
    return json.dumps({"tasks": list(tasks)})


def extract(*script: str) -> tuple[ExtractionSuccess | ExtractionFailure, ScriptedProvider]:
    provider = ScriptedProvider(*script)
    extractor = BasicExtractor(provider, ExtractionConfig(), sleep=lambda _seconds: None)
    return extractor.extract(MEETING, SourceType.MEETING, item_key="meetings/is1008b"), provider


def test_every_task_comes_back_with_a_confidence_of_its_own() -> None:
    invented = {**LAB, "source_ref": "LAB-0001"}

    outcome, _ = extract(answer(MINUTES, invented))

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.item_key == "meetings/is1008b"
    minutes, lab = outcome.tasks
    assert (minutes.fields.source_ref, lab.fields.source_ref) == ("MSG-4471", None)
    assert "invented_source_ref" in [p.reason for p in lab.confidence.penalties]
    assert "invented_source_ref" not in [p.reason for p in minutes.confidence.penalties]


def test_a_text_with_no_task_is_an_empty_success() -> None:
    outcome, provider = extract(answer())

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.tasks == ()
    assert provider.call_count == 1


def test_one_invalid_task_sends_the_whole_answer_to_repair() -> None:
    outcome, provider = extract(answer(MINUTES, {**LAB, "title": "N/A"}), answer(MINUTES, LAB))

    assert isinstance(outcome, ExtractionSuccess)
    assert [task.fields.title for task in outcome.tasks] == [MINUTES["title"], LAB["title"]]
    assert "tasks.1.title" in provider.calls[1][1]


def test_more_than_the_cap_is_never_accepted() -> None:
    too_many = answer(*[LAB] * (MAX_TASKS_PER_TEXT + 1))

    outcome, _ = extract(*[too_many] * ExtractionConfig().max_attempts)

    assert isinstance(outcome, ExtractionFailure)
    assert outcome.item_key == "meetings/is1008b"


# --- the repair penalty falls on the task that needed it ------------------


def repair_penalty(task: ExtractedTask) -> bool:
    return "repair_attempts" in [p.reason for p in task.confidence.penalties]


def test_a_task_copied_unchanged_through_repair_is_not_charged_for_it() -> None:
    broken = {**LAB, "title": "N/A"}

    outcome, _ = extract(answer(MINUTES, broken), answer(LAB, MINUTES))

    assert isinstance(outcome, ExtractionSuccess)
    lab, minutes = outcome.tasks
    assert not repair_penalty(minutes)
    assert repair_penalty(lab)


def test_a_valid_task_reworded_in_repair_is_charged_and_logged(
    caplog: pytest.LogCaptureFixture,
) -> None:
    reworded = {**MINUTES, "title": "Send the client the minutes"}

    with caplog.at_level(logging.INFO, logger="mind.extraction"):
        outcome, _ = extract(answer(MINUTES, {**LAB, "title": "N/A"}), answer(reworded, LAB))

    assert isinstance(outcome, ExtractionSuccess)
    assert all(repair_penalty(task) for task in outcome.tasks)
    assert "1 of 1 task(s) valid in an earlier round did not come back unchanged" in caplog.text


# --- one proposal per task ------------------------------------------------


def test_every_task_becomes_its_own_proposal() -> None:
    outcome, _ = extract(answer(MINUTES, {**LAB, "source_ref": "LAB-0001"}))
    assert isinstance(outcome, ExtractionSuccess)
    tenant_id = uuid4()

    proposals = to_task_creation_requested(outcome, tenant_id=tenant_id)

    assert [p.title for p in proposals] == [MINUTES["title"], LAB["title"]]
    assert [p.confidence for p in proposals] == [t.confidence.value for t in outcome.tasks]
    assert proposals[0].confidence > proposals[1].confidence
    assert {(p.tenant_id, p.source_type) for p in proposals} == {(tenant_id, SourceType.MEETING)}


def test_no_task_means_no_proposal() -> None:
    outcome, _ = extract(answer())
    assert isinstance(outcome, ExtractionSuccess)

    assert to_task_creation_requested(outcome, tenant_id=uuid4()) == []
