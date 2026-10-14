"""Tests for reading an LLM answer into validated fields."""

import json

import pytest

from mind.extraction.parsing import MalformedOutput, parse_response

VALID = json.dumps(
    {
        "title": "Send the Q3 report",
        "description": "Send it to the board before Friday.",
        "source_ref": "msg-8821",
        "has_dependencies": False,
    }
)


def test_reads_a_plain_json_answer() -> None:
    fields = parse_response(VALID)

    assert fields.title == "Send the Q3 report"
    assert fields.has_dependencies is False


def test_forgives_a_markdown_code_fence() -> None:
    """A fence is a presentation slip, and forgiving it saves a whole round."""

    fields = parse_response(f"```json\n{VALID}\n```")

    assert fields.title == "Send the Q3 report"


def test_forgives_chatter_around_the_object() -> None:
    fields = parse_response(f"Sure! Here is the JSON:\n{VALID}\nLet me know if that helps.")

    assert fields.title == "Send the Q3 report"


@pytest.mark.parametrize(
    ("answer", "expected"),
    [
        ("", "empty"),
        ("   ", "empty"),
        ("I could not find a task here.", "no JSON object"),
        ('{"title": "Send the report",}', "not valid JSON"),
        ("[1, 2, 3]", "no JSON object"),
    ],
)
def test_unreadable_answers_are_rejected_with_a_usable_reason(
    answer: str, expected: str
) -> None:
    with pytest.raises(MalformedOutput, match=expected):
        parse_response(answer)


def test_missing_required_field_names_the_field() -> None:
    with pytest.raises(MalformedOutput, match="has_dependencies"):
        parse_response('{"title": "Send the report"}')


def test_wrong_type_explains_the_type() -> None:
    with pytest.raises(MalformedOutput, match="title: Input should be a valid string"):
        parse_response('{"title": 12, "has_dependencies": false}')


def test_extra_field_is_rejected_by_name() -> None:
    answer = json.dumps(
        {"title": "Send the report", "has_dependencies": False, "urgency": 5}
    )

    with pytest.raises(MalformedOutput, match="urgency"):
        parse_response(answer)


def test_several_problems_are_all_reported() -> None:
    answer = json.dumps({"title": "", "has_dependencies": "maybe"})

    with pytest.raises(MalformedOutput) as caught:
        parse_response(answer)

    assert "title" in str(caught.value)
    assert "has_dependencies" in str(caught.value)
