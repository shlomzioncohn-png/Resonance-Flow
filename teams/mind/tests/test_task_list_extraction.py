"""Acceptance tests for extracting every task a text describes (#690)."""

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
from mind.extraction.prompts import MAX_CONTENT_CHARS
from tests.extraction.fakes import DEADLINE_SETTINGS, WRITTEN_AT, ScriptedProvider, answer_of

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


def extract(*script: str) -> tuple[ExtractionSuccess | ExtractionFailure, ScriptedProvider]:
    provider = ScriptedProvider(*script)
    extractor = BasicExtractor(provider, ExtractionConfig(), sleep=lambda _seconds: None)
    return extractor.extract(MEETING, SourceType.MEETING, item_key="meetings/is1008b"), provider


def test_every_task_comes_back_with_a_confidence_of_its_own() -> None:
    invented = {**LAB, "source_ref": "LAB-0001"}

    outcome, _ = extract(answer_of(MINUTES, invented))

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.item_key == "meetings/is1008b"
    minutes, lab = outcome.tasks
    assert (minutes.fields.source_ref, lab.fields.source_ref) == ("MSG-4471", None)
    assert "invented_source_ref" in [p.reason for p in lab.confidence.penalties]
    assert "invented_source_ref" not in [p.reason for p in minutes.confidence.penalties]


def test_a_text_with_no_task_is_an_empty_success() -> None:
    outcome, provider = extract(answer_of())

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.tasks == ()
    assert provider.call_count == 1


def test_one_invalid_task_sends_the_whole_answer_to_repair() -> None:
    outcome, provider = extract(answer_of(MINUTES, {**LAB, "title": "N/A"}), answer_of(MINUTES, LAB))

    assert isinstance(outcome, ExtractionSuccess)
    assert [task.fields.title for task in outcome.tasks] == [MINUTES["title"], LAB["title"]]
    assert "tasks.1.title" in provider.calls[1][1]


def test_more_than_the_cap_is_never_accepted() -> None:
    too_many = answer_of(*[LAB] * (MAX_TASKS_PER_TEXT + 1))

    outcome, _ = extract(*[too_many] * ExtractionConfig().max_attempts)

    assert isinstance(outcome, ExtractionFailure)
    assert outcome.item_key == "meetings/is1008b"


# --- the repair penalty falls on the task that needed it ------------------


def repair_penalty(task: ExtractedTask) -> bool:
    return "repair_attempts" in [p.reason for p in task.confidence.penalties]


def test_a_task_copied_unchanged_through_repair_is_not_charged_for_it() -> None:
    broken = {**LAB, "title": "N/A"}

    outcome, _ = extract(answer_of(MINUTES, broken), answer_of(LAB, MINUTES))

    assert isinstance(outcome, ExtractionSuccess)
    lab, minutes = outcome.tasks
    assert not repair_penalty(minutes)
    assert repair_penalty(lab)


def test_a_valid_task_reworded_in_repair_is_charged_and_logged(
    caplog: pytest.LogCaptureFixture,
) -> None:
    reworded = {**MINUTES, "title": "Send the client the minutes"}

    with caplog.at_level(logging.INFO, logger="mind.extraction"):
        outcome, _ = extract(answer_of(MINUTES, {**LAB, "title": "N/A"}), answer_of(reworded, LAB))

    assert isinstance(outcome, ExtractionSuccess)
    assert all(repair_penalty(task) for task in outcome.tasks)
    assert (
        "the rejected answer had 2 task(s), 1 of them valid; the repaired answer has 2, "
        "and 0 of the valid ones came back unchanged"
    ) in caplog.text
    assert "WARNING" not in caplog.text


def test_only_the_last_rejected_round_is_counted(caplog: pytest.LogCaptureFixture) -> None:
    """A task valid in two rejected rounds is one task, not two."""

    rejected = answer_of(MINUTES, {**LAB, "title": "N/A"})

    with caplog.at_level(logging.INFO, logger="mind.extraction"):
        extract(rejected, rejected, answer_of(MINUTES, LAB))

    assert "had 2 task(s), 1 of them valid" in caplog.text
    assert "2 of them valid" not in caplog.text


def test_dropping_the_invalid_task_is_expected_and_quiet(caplog: pytest.LogCaptureFixture) -> None:
    """The title message tells the model to leave a non-task out; it did."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        outcome, _ = extract(answer_of(MINUTES, {**LAB, "title": "N/A"}), answer_of(MINUTES))

    assert isinstance(outcome, ExtractionSuccess)
    assert len(outcome.tasks) == 1
    assert caplog.text == ""


def test_a_valid_task_missing_after_repair_is_a_warning(caplog: pytest.LogCaptureFixture) -> None:
    """Decision 3 of #690, undone by the model: nothing raises, but it is not silent."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        extract(answer_of(MINUTES, {**LAB, "title": "N/A"}), answer_of(LAB))

    assert "may have been dropped" in caplog.text


def test_an_answer_over_the_cap_earns_no_task_any_credit(caplog: pytest.LogCaptureFixture) -> None:
    """A model listing more than the cap is listing non-tasks, so none of its
    tasks counts as right the first time."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        outcome, _ = extract(
            answer_of(*[LAB] * (MAX_TASKS_PER_TEXT + 1)), answer_of(*[LAB] * MAX_TASKS_PER_TEXT)
        )

    assert isinstance(outcome, ExtractionSuccess)
    assert all(repair_penalty(task) for task in outcome.tasks)
    assert caplog.text == ""


# --- one proposal per task ------------------------------------------------


def test_every_task_becomes_its_own_proposal() -> None:
    outcome, _ = extract(answer_of(MINUTES, {**LAB, "source_ref": "LAB-0001"}))
    assert isinstance(outcome, ExtractionSuccess)
    tenant_id = uuid4()

    proposals = to_task_creation_requested(
        outcome,
        tenant_id=tenant_id,
        bridge_source_ref="C024BE91L:1728900000.000100",
        bridge_correlation_id=uuid4(),
        written_at=WRITTEN_AT,
        deadline_settings=DEADLINE_SETTINGS,
    )

    assert [p.title for p in proposals] == [MINUTES["title"], LAB["title"]]
    assert [p.confidence for p in proposals] == [t.confidence.value for t in outcome.tasks]
    assert proposals[0].confidence > proposals[1].confidence
    # One item, so one source_ref: it says where the tasks came from, not which.
    assert {(p.tenant_id, p.source_type, p.source_ref) for p in proposals} == {
        (tenant_id, SourceType.MEETING, "C024BE91L:1728900000.000100")
    }


def test_no_task_means_no_proposal() -> None:
    outcome, _ = extract(answer_of())
    assert isinstance(outcome, ExtractionSuccess)

    proposals = to_task_creation_requested(
        outcome,
        tenant_id=uuid4(),
        bridge_source_ref=None,
        bridge_correlation_id=uuid4(),
        written_at=WRITTEN_AT,
        deadline_settings=DEADLINE_SETTINGS,
    )

    assert proposals == []


# --- what the logs and the grounding read ----------------------------------


def test_an_unreadable_rejected_answer_is_logged_as_unreadable(
    caplog: pytest.LogCaptureFixture,
) -> None:
    with caplog.at_level(logging.INFO, logger="mind.extraction"):
        extract('{"tasks": [{"title": "A",}]}', answer_of(MINUTES))

    assert "the rejected answer could not be read" in caplog.text
    assert "had 0 task(s)" not in caplog.text


def test_grounding_reads_only_the_text_the_model_was_sent() -> None:
    """A pointer past the cut was out of the model's sight, so naming it was a guess."""

    text = "Please book the lab for the prototype test. " + "x" * MAX_CONTENT_CHARS + " DES-2214"
    provider = ScriptedProvider(answer_of({**LAB, "source_ref": "DES-2214"}))

    outcome = BasicExtractor(provider, sleep=lambda _seconds: None).extract(text, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionSuccess)
    [task] = outcome.tasks
    assert task.fields.source_ref is None
    assert "invented_source_ref" in [p.reason for p in task.confidence.penalties]


def test_a_meeting_is_grounded_against_all_of_what_it_sent() -> None:
    """Past 8000 characters, but inside a meeting's own cap, the model saw it."""

    text = "Please book the lab for the prototype test. " + "x" * MAX_CONTENT_CHARS + " DES-2214"
    provider = ScriptedProvider(answer_of({**LAB, "source_ref": "DES-2214"}))

    outcome = BasicExtractor(provider, sleep=lambda _seconds: None).extract(
        text, SourceType.MEETING
    )

    assert isinstance(outcome, ExtractionSuccess)
    [task] = outcome.tasks
    assert task.fields.source_ref == "DES-2214"
