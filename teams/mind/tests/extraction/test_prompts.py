"""Tests for prompt loading and filling."""

import pytest

from mind.contracts.base_enums import SourceType
from mind.extraction.prompts import (
    MAX_CONTENT_CHARS,
    TRUNCATION_MARKER,
    _fill,
    extraction_prompt,
    repair_prompt,
    system_prompt,
    truncate,
)


def test_system_prompt_names_every_field_and_forbids_extras() -> None:
    prompt = system_prompt()

    for field in ("title", "description", "source_ref", "has_dependencies"):
        assert field in prompt
    assert "null" in prompt
    # Worded without a count on purpose: the same sentence has to hold when
    # the classification block adds three more keys below it.
    assert "Exactly the keys listed above" in prompt


def test_extraction_prompt_carries_source_type_and_content() -> None:
    prompt = extraction_prompt(SourceType.SLACK, "please review the deploy checklist")

    assert "SLACK" in prompt
    assert "please review the deploy checklist" in prompt
    assert "{{" not in prompt


def test_repair_prompt_returns_the_rejected_answer_and_the_reason() -> None:
    prompt = repair_prompt(
        SourceType.EMAIL,
        "send the report",
        invalid_output='{"title": 12}',
        error="title: Input should be a valid string",
    )

    assert '{"title": 12}' in prompt
    assert "Input should be a valid string" in prompt
    assert "{{" not in prompt


def test_long_content_is_truncated_with_a_visible_marker() -> None:
    content = "x" * (MAX_CONTENT_CHARS + 500)

    truncated = truncate(content)

    assert truncated.endswith(TRUNCATION_MARKER)
    assert len(truncated) == MAX_CONTENT_CHARS + len(TRUNCATION_MARKER)


def test_short_content_is_left_alone() -> None:
    assert truncate("send the report") == "send the report"


def test_unfilled_placeholder_is_an_error_not_a_silent_gap() -> None:
    with pytest.raises(ValueError, match="unfilled placeholders"):
        _fill("Source: {{SOURCE_TYPE}} / Content: {{CONTENT}}", {"SOURCE_TYPE": "EMAIL"})


def test_json_braces_in_content_survive_filling() -> None:
    prompt = extraction_prompt(SourceType.DOC, 'the payload was {"id": 7}')

    assert '{"id": 7}' in prompt


# --- the classification block (#351) -------------------------------------


def test_the_switch_off_prompt_never_mentions_classification() -> None:
    """A model cannot volunteer a key it was never told about."""

    prompt = system_prompt()

    for word in ("tier", "urgency", "role_context", "STRATEGIC"):
        assert word not in prompt


def test_the_switch_on_prompt_adds_the_three_and_keeps_the_four() -> None:
    prompt = system_prompt(classify=True)

    for field in ("title", "source_ref", "tier", "urgency", "role_context"):
        assert field in prompt
    assert prompt.startswith(system_prompt())


def test_the_classification_block_spells_out_every_permitted_value() -> None:
    """Categorical fields need the vocabulary written out, or the model
    invents a neighbouring word that then fails validation."""

    prompt = system_prompt(classify=True)

    for value in ("OPERATIONAL", "TACTICAL", "STRATEGIC"):
        assert value in prompt
    for value in ("DEVELOPER", "MANAGER", "HIGHER_MANAGER", "FIELD_ENGINEER",
                  "SALESPERSON", "CEO"):
        assert value in prompt


def test_the_classification_block_says_null_is_a_correct_answer() -> None:
    prompt = system_prompt(classify=True)

    assert "null" in prompt
    assert "better than a guess" in prompt
