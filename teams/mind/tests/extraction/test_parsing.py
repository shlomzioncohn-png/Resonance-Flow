"""Tests for reading an LLM answer into validated fields."""

import json

import pytest

from mind.extraction.models import ExtractedFieldsWithClassification
from mind.extraction.parsing import MalformedOutput, parse_response, valid_tasks

TASK = {
    "title": "Send the Q3 report",
    "description": "Send it to the board before Friday.",
    "source_ref": "msg-8821",
    "has_dependencies": False,
}


def answer(*tasks: object) -> str:
    """The answer as the model writes it: every task inside one object (#690)."""

    return json.dumps({"tasks": list(tasks)})


VALID = answer(TASK)


def test_reads_a_plain_json_answer() -> None:
    [fields] = parse_response(VALID)

    assert fields.title == "Send the Q3 report"
    assert fields.has_dependencies is False


def test_forgives_a_markdown_code_fence() -> None:
    """A fence is a presentation slip, and forgiving it saves a whole round."""

    [fields] = parse_response(f"```json\n{VALID}\n```")

    assert fields.title == "Send the Q3 report"


def test_forgives_chatter_around_the_object() -> None:
    [fields] = parse_response(f"Sure! Here is the JSON:\n{VALID}\nLet me know if that helps.")

    assert fields.title == "Send the Q3 report"


@pytest.mark.parametrize(
    ("answer", "expected"),
    [
        ("", "empty"),
        ("   ", "empty"),
        ("I could not find a task here.", "no JSON object"),
        ('{"tasks": [{"title": "Send the report",}]}', "not valid JSON"),
        ("[1, 2, 3]", "a list, not the object"),
    ],
)
def test_unreadable_answers_are_rejected_with_a_usable_reason(
    answer: str, expected: str
) -> None:
    with pytest.raises(MalformedOutput, match=expected):
        parse_response(answer)


def test_missing_required_field_names_the_field() -> None:
    with pytest.raises(MalformedOutput, match="has_dependencies"):
        parse_response(answer({"title": "Send the report"}))


def test_wrong_type_explains_the_type() -> None:
    with pytest.raises(MalformedOutput, match="title: Input should be a valid string"):
        parse_response(answer({"title": 12, "has_dependencies": False}))


def test_extra_field_is_rejected_by_name() -> None:
    extra = answer({"title": "Send the report", "has_dependencies": False, "urgency": 5})

    with pytest.raises(MalformedOutput, match="urgency"):
        parse_response(extra)


def test_several_problems_are_all_reported() -> None:
    broken = answer({"title": "", "has_dependencies": "maybe"})

    with pytest.raises(MalformedOutput) as caught:
        parse_response(broken)

    assert "title" in str(caught.value)
    assert "has_dependencies" in str(caught.value)


# --- the answer is a list of tasks (#690) ---------------------------------


def test_every_task_comes_back_in_the_order_written() -> None:
    second = {**TASK, "title": "Book the board room"}

    titles = [fields.title for fields in parse_response(answer(TASK, second))]

    assert titles == ["Send the Q3 report", "Book the board room"]


def test_no_tasks_is_an_answer_not_a_failure() -> None:
    assert parse_response(answer()) == []


def test_each_task_is_read_into_the_requested_schema() -> None:
    [fields] = parse_response(answer({**TASK, "urgency": 4}), ExtractedFieldsWithClassification)

    assert isinstance(fields, ExtractedFieldsWithClassification)
    assert fields.urgency == 4


def test_the_rejection_names_the_task_by_its_place_in_the_list() -> None:
    with pytest.raises(MalformedOutput, match="tasks.1.title"):
        parse_response(answer(TASK, {**TASK, "title": 12}))


def test_a_single_task_in_the_old_shape_is_told_what_is_missing() -> None:
    with pytest.raises(MalformedOutput, match="tasks: Field required"):
        parse_response(json.dumps(TASK))


@pytest.mark.parametrize("count", [1, 2])
def test_a_bare_list_of_tasks_is_told_to_wrap_them(count: int) -> None:
    """The JSON is valid, so "not valid JSON" would send the model the wrong way."""

    bare = json.dumps([TASK] * count)

    with pytest.raises(MalformedOutput, match='a list, not the object.*"tasks"'):
        parse_response(f"```json\n{bare}\n```")


def test_the_valid_tasks_of_a_rejected_answer_are_found_one_by_one() -> None:
    rejected = answer(TASK, {**TASK, "title": "N/A"}, "not a task")

    assert [fields.title for fields in valid_tasks(rejected)] == ["Send the Q3 report"]


@pytest.mark.parametrize("rejected", ["", "no JSON", "[1]", '{"tasks": 3}', '{"tasks": [}'])
def test_finding_valid_tasks_never_raises(rejected: str) -> None:
    assert valid_tasks(rejected) == []
