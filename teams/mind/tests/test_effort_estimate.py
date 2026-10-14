"""The effort estimate (#352), through the whole extractor and into the contract.

`tests/extraction/test_effort_fields.py` checks the shape in isolation. This
file walks the issue's acceptance criteria, one test each, from a scripted
answer to the `TaskCreationRequested` that Inbox would receive.

A scripted provider returns what the test wrote, so these prove that our code
neither invents an estimate nor loses one. Whether a real model answers null
on text with no signal is a question for a live run, not for this file.
"""

import json
from uuid import uuid4

import pytest

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.confidence import effort_confidence
from mind.extraction.extractor import BasicExtractor, ExtractionSuccess
from mind.extraction.models import EffortBasis
from tests.extraction.fakes import ScriptedProvider

SOURCE_TEXT = "Could you update the onboarding checklist? Should take about an hour."


def _answer(minutes: object, basis: object) -> str:
    return json.dumps(
        {
            "title": "Update the onboarding checklist",
            "description": None,
            "source_ref": None,
            "has_dependencies": False,
            "tier": "OPERATIONAL",
            "urgency": 2,
            "role_context": None,
            "effort_estimate_minutes": minutes,
            "effort_estimate_basis": basis,
        }
    )


def _proposal(answer: str) -> tuple[TaskCreationRequested, ScriptedProvider]:
    # Three answers queued, so a repair round would be counted rather than crash.
    provider = ScriptedProvider(answer, answer, answer)
    outcome = BasicExtractor(provider, sleep=lambda _: None).extract(
        SOURCE_TEXT, SourceType.EMAIL
    )
    assert isinstance(outcome, ExtractionSuccess)
    return to_task_creation_requested(outcome, tenant_id=uuid4()), provider


def test_a_clear_duration_signal_is_extracted() -> None:
    proposal, _ = _proposal(_answer(60, "STATED"))

    assert proposal.effort_estimate_minutes == 60
    assert proposal.effort_estimate_confidence == effort_confidence(EffortBasis.STATED)


def test_no_duration_signal_leaves_both_fields_null() -> None:
    """Nothing on our side fills the gap: no default, no fallback number."""

    proposal, _ = _proposal(_answer(None, None))

    assert proposal.effort_estimate_minutes is None
    assert proposal.effort_estimate_confidence is None


@pytest.mark.parametrize(("minutes", "basis"), [(60, "STATED"), (15, "INFERRED"), (15, None)])
def test_every_estimate_arrives_with_a_confidence(minutes: int, basis: str | None) -> None:
    """Including minutes the model sent without a basis: they arrive as inferred."""

    proposal, _ = _proposal(_answer(minutes, basis))

    assert proposal.effort_estimate_minutes == minutes
    assert proposal.effort_estimate_confidence is not None


@pytest.mark.parametrize(("minutes", "basis"), [(None, None), ("an hour", "STATED")])
def test_a_task_without_an_estimate_is_created_at_once(minutes: object, basis: object) -> None:
    """No clarification, no waiting, no second call: the task is simply made.

    One provider call and a complete proposal, whether the estimate was
    honestly absent or came back unusable.
    """

    proposal, provider = _proposal(_answer(minutes, basis))

    assert provider.call_count == 1
    assert proposal.title == "Update the onboarding checklist"
    assert proposal.effort_estimate_minutes is None
