"""What the three classification fields accept, and what they refuse.

Read this file as the specification: every test name is a sentence about how
`ClassificationFields` is supposed to behave, and the body is the example that
proves it.

The line it draws is the same one `parsing.py` draws for the answer as a whole
— forgive formatting, never forgive content — with one addition that only
applies here. For these fields, "I don't know" is a *correct* answer, so a
model saying so must not be treated as a model getting it wrong.
"""

import logging

import pytest
from pydantic import ValidationError

from mind.contracts.task_enums import RoleContext, TaskTier
from mind.extraction.models import (
    ClassificationFields,
    ExtractedFields,
    ExtractedFieldsWithClassification,
    response_model,
)

# --- the happy path ------------------------------------------------------


def test_the_three_fields_come_back_as_their_enums() -> None:
    fields = ClassificationFields(tier="STRATEGIC", urgency=4, role_context="CEO")

    assert fields.tier is TaskTier.STRATEGIC
    assert fields.role_context is RoleContext.CEO
    assert fields.urgency == 4


def test_everything_is_optional_because_null_is_a_real_answer() -> None:
    """An item nobody can classify is still a valid extraction."""

    fields = ClassificationFields()

    assert fields.tier is None
    assert fields.urgency is None
    assert fields.role_context is None


# --- forgiving the format ------------------------------------------------


@pytest.mark.parametrize("written", ["strategic", "Strategic", "  STRATEGIC  "])
def test_case_and_spacing_are_forgiven(written: str) -> None:
    """A model that means the right thing in the wrong case still means it."""

    assert ClassificationFields(tier=written).tier is TaskTier.STRATEGIC


def test_a_multi_word_role_survives_its_underscore() -> None:
    assert ClassificationFields(role_context="higher_manager").role_context is (
        RoleContext.HIGHER_MANAGER
    )


# --- "I don't know" is an answer, not a mistake --------------------------


@pytest.mark.parametrize("written", ["unknown", "N/A", "none", "", "  ", "not specified"])
def test_the_models_ways_of_saying_nothing_become_null(written: str) -> None:
    """Rejecting these would spend a repair round on a usable answer.

    The model did not invent anything here — it declined to guess, which is
    what the prompt asks of it. Treating that as malformed output would send
    it back a question it already answered correctly.
    """

    assert ClassificationFields(tier=written).tier is None


def test_a_placeholder_in_urgency_becomes_null_rather_than_a_number() -> None:
    assert ClassificationFields(urgency="unknown").urgency is None


# --- refusing the content ------------------------------------------------


def test_an_invented_tier_becomes_null_instead_of_failing_the_item() -> None:
    """`critical` is the model inventing a value — and a null is the answer.

    These three fields are optional by design, and the prompt tells the model
    that null is correct when the content is unclear. A model that reached for
    a word outside the vocabulary has shown exactly that. Failing instead
    would cost up to three calls and then throw away the title, description
    and source_ref that came back perfectly well in the same answer.
    """

    assert ClassificationFields(tier="critical").tier is None


def test_an_invented_role_becomes_null() -> None:
    assert ClassificationFields(role_context="intern").role_context is None


def test_dropping_a_value_is_never_silent(caplog: pytest.LogCaptureFixture) -> None:
    """Otherwise a model inventing values all day looks like an honest one.

    Both end in a null, so without the log there would be no way to tell "the
    content did not say" from "the model keeps making things up".
    """

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        ClassificationFields(tier="critical")

    assert "critical" in caplog.text
    assert "STRATEGIC" in caplog.text


@pytest.mark.parametrize("out_of_range", [0, 6, -1, 99])
def test_urgency_outside_the_scale_becomes_null_rather_than_clamped(
    out_of_range: int,
) -> None:
    """Not clamped to 5, on purpose.

    A 7 might mean "past the top of your scale", or it might mean the model
    scored on 1 to 10, where 7 is middling. Those look identical from here,
    and the costs are not symmetric: a missing urgency is visibly missing,
    while a clamped one is a plausible number that quietly inflates ranking.
    """

    assert ClassificationFields(urgency=out_of_range).urgency is None


@pytest.mark.parametrize("not_a_number", ["high", "3.5", True])
def test_an_urgency_that_is_not_a_whole_number_becomes_null(not_a_number: object) -> None:
    assert ClassificationFields(urgency=not_a_number).urgency is None


def test_a_required_field_still_fails_loudly() -> None:
    """The asymmetry this whole decision rests on.

    `title` is required by the contract, so a bad one is a real failure and
    still earns a repair round. The classification fields are optional, so a
    bad one can honestly become the null they were always allowed to be.
    """

    with pytest.raises(ValidationError):
        ExtractedFields(has_dependencies=False)


def test_a_numeric_string_urgency_is_still_a_number() -> None:
    """Models quote numbers. That is formatting, not content."""

    assert ClassificationFields(urgency="3").urgency == 3


def test_an_unexpected_field_is_rejected() -> None:
    """Same `extra="forbid"` stance as the rest of the extraction."""

    with pytest.raises(ValidationError):
        ClassificationFields(tier="TACTICAL", priority="high")


# --- the combined shape, and which shape gets used -----------------------


def test_the_combined_shape_takes_all_seven() -> None:
    fields = ExtractedFieldsWithClassification(
        title="Send the Q3 report",
        description=None,
        source_ref="MSG-4471",
        has_dependencies=False,
        tier="STRATEGIC",
        urgency=4,
        role_context="CEO",
    )

    assert fields.title == "Send the Q3 report"
    assert fields.tier is TaskTier.STRATEGIC
    assert fields.role_context is RoleContext.CEO


def test_the_combined_shape_keeps_both_sets_of_rules() -> None:
    """A missing title is still fatal; an invented tier becomes a null."""

    with pytest.raises(ValidationError):
        ExtractedFieldsWithClassification(has_dependencies=False)

    salvaged = ExtractedFieldsWithClassification(
        title="x", has_dependencies=False, tier="critical"
    )
    assert salvaged.title == "x"
    assert salvaged.tier is None


def test_classification_keys_stay_optional_even_when_asked_for() -> None:
    """Asking for them does not make answering them compulsory."""

    fields = ExtractedFieldsWithClassification(title="x", has_dependencies=False)

    assert fields.tier is None
    assert fields.urgency is None
    assert fields.role_context is None


def test_the_basic_shape_refuses_a_classification_field() -> None:
    """With the switch off we never asked for tier, so tier is an intrusion."""

    with pytest.raises(ValidationError):
        ExtractedFields(title="x", has_dependencies=False, tier="STRATEGIC")


def test_the_combined_shape_is_also_a_basic_one() -> None:
    """What lets confidence.py and assembly.py stay untouched by all this."""

    fields = ExtractedFieldsWithClassification(title="x", has_dependencies=False)

    assert isinstance(fields, ExtractedFields)


def test_the_switch_picks_the_shape() -> None:
    assert response_model(classify=True) is ExtractedFieldsWithClassification
    assert response_model(classify=False) is ExtractedFields
