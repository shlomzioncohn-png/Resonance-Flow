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
    assert "Exactly those four keys" in prompt


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
