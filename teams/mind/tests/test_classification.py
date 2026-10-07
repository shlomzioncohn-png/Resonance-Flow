"""Classification at extraction time (#351), through the whole extractor.

`tests/extraction/test_classification_fields.py` checks the shape in
isolation. This file checks the switch: that asking for the three fields and
accepting them are the same decision, and that turning it off really does
turn it off rather than merely leaving the fields empty.
"""

import json
from uuid import uuid4

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.contracts.task_enums import RoleContext, TaskTier
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.config import ExtractionConfig
from mind.extraction.extractor import BasicExtractor, ExtractionSuccess
from tests.extraction.fakes import ScriptedProvider

SOURCE_TEXT = (
    "The board meets Thursday and still has no decision on whether we are "
    "entering the German market. I need the recommendation written before then."
)

CLASSIFIED_ANSWER = json.dumps(
    {
        "title": "Write the German market entry recommendation",
        "description": "The board needs it before Thursday's meeting.",
        "source_ref": None,
        "has_dependencies": False,
        "tier": "STRATEGIC",
        "urgency": 4,
        "role_context": "CEO",
    }
)

BASIC_ANSWER = json.dumps(
    {
        "title": "Write the German market entry recommendation",
        "description": "The board needs it before Thursday's meeting.",
        "source_ref": None,
        "has_dependencies": False,
    }
)


def _extract(answer: str, classify: bool) -> tuple[object, ScriptedProvider]:
    provider = ScriptedProvider(answer)
    config = ExtractionConfig(classify=classify)
    outcome = BasicExtractor(provider, config=config, sleep=lambda _: None).extract(
        SOURCE_TEXT, SourceType.EMAIL
    )
    return outcome, provider


def test_with_the_switch_on_the_three_fields_arrive() -> None:
    outcome, _ = _extract(CLASSIFIED_ANSWER, classify=True)

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.fields.tier is TaskTier.STRATEGIC
    assert outcome.fields.role_context is RoleContext.CEO
    assert outcome.fields.urgency == 4


def test_with_the_switch_on_the_model_is_told_about_them() -> None:
    _, provider = _extract(CLASSIFIED_ANSWER, classify=True)

    system = provider.calls[0][0]
    for word in ("tier", "urgency", "role_context", "STRATEGIC"):
        assert word in system


def test_with_the_switch_off_the_model_is_never_told_about_them() -> None:
    _, provider = _extract(BASIC_ANSWER, classify=False)

    system = provider.calls[0][0]
    for word in ("tier", "urgency", "role_context"):
        assert word not in system


def test_with_the_switch_off_a_classified_answer_is_refused() -> None:
    """The proof the switch is real and not decoration.

    The same answer that is correct with the switch on is an unrequested
    field with it off, and gets sent back for repair rather than quietly
    accepted. That is what makes a before-and-after comparison meaningful:
    the two configurations genuinely differ.
    """

    provider = ScriptedProvider(CLASSIFIED_ANSWER, CLASSIFIED_ANSWER, CLASSIFIED_ANSWER)
    config = ExtractionConfig(classify=False)

    outcome = BasicExtractor(provider, config=config, sleep=lambda _: None).extract(
        SOURCE_TEXT, SourceType.EMAIL
    )

    assert not isinstance(outcome, ExtractionSuccess)
    assert "tier" in outcome.detail

    # And it costs the whole repair budget to find that out, which is why the
    # switched-off prompt must not so much as mention the word: a model that
    # never hears "tier" does not volunteer one.
    assert provider.call_count == config.max_attempts


def test_the_four_basic_fields_are_unaffected_either_way() -> None:
    """Whatever the switch, the rest of the pipeline sees what it always saw."""

    on, _ = _extract(CLASSIFIED_ANSWER, classify=True)
    off, _ = _extract(BASIC_ANSWER, classify=False)

    assert isinstance(on, ExtractionSuccess) and isinstance(off, ExtractionSuccess)
    assert on.fields.title == off.fields.title
    assert on.fields.has_dependencies == off.fields.has_dependencies


# --- reaching the contract ------------------------------------------------


def _proposal(answer: str, classify: bool) -> TaskCreationRequested:
    provider = ScriptedProvider(answer)
    config = ExtractionConfig(classify=classify)
    outcome = BasicExtractor(provider, config=config, sleep=lambda _: None).extract(
        SOURCE_TEXT, SourceType.EMAIL
    )
    assert isinstance(outcome, ExtractionSuccess)
    return to_task_creation_requested(outcome, tenant_id=uuid4())


def test_the_three_fields_reach_the_contract() -> None:
    proposal = _proposal(CLASSIFIED_ANSWER, classify=True)

    assert proposal.tier is TaskTier.STRATEGIC
    assert proposal.urgency == 4
    assert proposal.role_context is RoleContext.CEO


def test_with_the_switch_off_the_contract_simply_gets_nulls() -> None:
    """Not a crash: the fields object has no `tier` attribute at all there."""

    proposal = _proposal(BASIC_ANSWER, classify=False)

    assert proposal.tier is None
    assert proposal.urgency is None
    assert proposal.role_context is None


def test_a_model_that_declined_to_guess_looks_the_same_as_not_asking() -> None:
    """Both are an empty field, and Inbox is meant to read them the same."""

    declined = json.dumps(
        {
            "title": "Write the German market entry recommendation",
            "description": None,
            "source_ref": None,
            "has_dependencies": False,
            "tier": None,
            "urgency": None,
            "role_context": "unknown",
        }
    )

    assert _proposal(declined, classify=True).role_context is None


def test_the_effort_fields_stay_for_352() -> None:
    proposal = _proposal(CLASSIFIED_ANSWER, classify=True)

    assert proposal.effort_estimate_minutes is None
    assert proposal.effort_estimate_confidence is None
