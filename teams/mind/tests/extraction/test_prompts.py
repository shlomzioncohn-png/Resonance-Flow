"""Tests for prompt loading and filling."""

import json
import logging

import pytest

from mind.contracts.base_enums import SourceType
from mind.extraction.models import MAX_TASKS_PER_TEXT, response_model
from mind.extraction.parsing import parse_response
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
    # Worded without a count and without a position on purpose: the same
    # sentence has to hold when the classification block adds its keys
    # *below* it. "The keys listed above" read literally excluded them.
    assert "Exactly the keys these instructions list" in prompt
    assert "listed above" not in system_prompt(classify=True)


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


def test_truncation_is_logged_with_how_much_was_cut(caplog: pytest.LogCaptureFixture) -> None:
    """A meeting's action items sit at its end, so a cut must not be silent (#690)."""

    content = "x" * (MAX_CONTENT_CHARS + 500)

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        extraction_prompt(SourceType.MEETING, content)
        repair_prompt(SourceType.MEETING, content, "{}", "error")

    # Once, from the first prompt: a repair round re-sends the same content.
    assert caplog.text.count("truncated") == 1
    assert f"MEETING content of {MAX_CONTENT_CHARS + 500} characters" in caplog.text
    assert "the last 500 are not sent" in caplog.text


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

    for word in ("tier", "urgency", "role_context", "STRATEGIC", "effort"):
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


def test_the_tiers_are_defined_by_time_horizon_not_by_who_does_the_work() -> None:
    """Keeps the prompt aligned with how tier is actually scored.

    `scoring/segmented_model.TIER_RULES` separates the tiers by scale_hours —
    8, 72, 336 — so a tier is a horizon, not a rank. The persona set says the
    same from the other side ("Persona != Tier": a developer can hold a
    strategic task). A prompt that sorted tasks by the kind of work instead
    would label a task one way and have it scored as if it were another.
    """

    prompt = system_prompt(classify=True)

    assert "hours to a day" in prompt
    assert "days to a week" in prompt
    assert "weeks or longer" in prompt
    # And says outright that tier is not the person.
    assert "not about who does it" in prompt


def test_every_role_is_described_and_not_merely_listed() -> None:
    """The issue asks for instructions per field, not a vocabulary list.

    Naming six roles tells a model nothing about what separates MANAGER from
    HIGHER_MANAGER, or FIELD_ENGINEER from DEVELOPER.
    """

    prompt = system_prompt(classify=True)

    for role, marker in (
        ("DEVELOPER", "code"),
        ("MANAGER", "one team"),
        ("HIGHER_MANAGER", "several teams"),
        ("FIELD_ENGINEER", "incidents"),
        ("SALESPERSON", "pipeline"),
        ("CEO", "board"),
    ):
        assert role in prompt
        assert marker in prompt


def _examples() -> list[dict[str, object]]:
    """Every example answer in the prompt, read the way the parser reads one.

    An example is any line that opens with a brace. The model imitates these
    far more closely than it follows the prose around them, so each one has
    to be an answer the parser would accept.
    """

    lines = system_prompt(classify=True).splitlines()
    return [json.loads(line) for line in lines if line.strip().startswith("{")]


def test_every_example_is_an_answer_the_parser_accepts() -> None:
    """Not only valid JSON: valid against the model, with every key it has.

    The count guards the guard. If the examples drift back into a format
    without braces, nothing is found, and a loop over nothing passes.
    The exact key set is what forces the examples to grow with the model:
    a field added in a later issue fails here until they show it too.
    """

    examples = _examples()
    shape = response_model(classify=True)

    assert len(examples) == 8
    for example in examples:
        for task in parse_response(json.dumps(example), shape):
            assert task.model_fields_set == set(shape.model_fields)


def test_the_examples_include_one_whose_answer_is_null() -> None:
    """An example of declining to answer is the one most worth giving."""

    tasks = [task for example in _examples() for task in example["tasks"]]
    assert any(task["role_context"] is None for task in tasks)
    assert "Null there is the right answer" in system_prompt(classify=True)


def test_the_prompt_warns_against_answering_with_two_labels() -> None:
    """The shape that used to end the whole run."""

    assert "never answer with a list of two" in system_prompt(classify=True)


# --- the effort estimate (#352) ------------------------------------------


def test_the_switch_on_prompt_asks_for_the_estimate_and_its_basis() -> None:
    prompt = system_prompt(classify=True)

    for word in ("effort_estimate_minutes", "effort_estimate_basis", "STATED", "INFERRED"):
        assert word in prompt


def test_the_classification_block_does_not_count_its_keys() -> None:
    """"These three" became wrong the moment #352 added two more.

    A count is a fact restated: the list itself already says how many there
    are, and the count is the copy that does not get updated.
    """

    prompt = system_prompt(classify=True).lower()

    for count in ("these three", "these five", "the three", "the five"):
        assert count not in prompt


def test_the_prompt_says_a_deadline_and_elapsed_time_are_not_effort() -> None:
    """The two duration-shaped phrases that are not estimates.

    In the persona set, every phrase that looks like a duration is one of
    these: "down for 30 minutes", "before this afternoon".
    """

    prompt = system_prompt(classify=True)

    assert "A deadline is not a duration" in prompt
    assert "Time that has already passed is not a duration" in prompt


def test_the_examples_show_every_kind_of_estimate() -> None:
    """Stated, inferred and none — the model imitates the examples it sees.

    With only nulls among them, null would look like the expected answer;
    with no stated one, the model would never see an estimate taken from
    the words rather than from the work.
    """

    bases = {
        task["effort_estimate_basis"] for example in _examples() for task in example["tasks"]
    }

    assert bases == {"STATED", "INFERRED", None}


def test_the_inferred_estimate_is_a_declared_exception_to_using_only_the_content() -> None:
    """`system.md` forbids the model's own knowledge; INFERRED needs exactly that.

    The exception is stated next to the field it is for, in the block that
    only exists when the switch is on. The rule itself stays for everything
    else, and stays the only thing a switched-off prompt says.
    """

    # Whitespace collapsed, so the check reads the wording and not where
    # the prompt file happens to wrap its lines.
    switched_on = " ".join(system_prompt(classify=True).split())

    assert "Do not carry over knowledge of your own" in system_prompt()
    assert "the one exception to basing every field on the content" in switched_on


def test_the_prompt_fixes_how_long_a_day_and_a_week_are() -> None:
    """Undefined, a week is 2400 minutes on one run and 10080 on the next.

    Both would arrive as STATED, at the highest confidence there is, four
    times apart.
    """

    prompt = " ".join(system_prompt(classify=True).split())

    assert "a day is 480" in prompt
    assert "a week is 2400" in prompt
    assert "working time, not calendar time" in prompt


def test_a_unit_whose_length_is_assumed_makes_the_estimate_inferred() -> None:
    """"Two sprints" is stated in words but not in minutes.

    How long a sprint is depends on the team, so the number is the model's
    assumption, and STATED's high confidence would claim more than the text
    did.
    """

    prompt = " ".join(system_prompt(classify=True).split())

    # Inside the definition of INFERRED, and only there. As a separate rule
    # it contradicted the definitions below it, which said that anything
    # stating a duration is STATED.
    assert "a unit whose length you would have to assume" in prompt
    assert prompt.count("two sprints") == 1


# --- the answer is a list of tasks (#690) ---------------------------------


@pytest.mark.parametrize("classify", [False, True])
def test_the_prompt_asks_for_the_list_and_allows_it_to_be_empty(classify: bool) -> None:
    prompt = system_prompt(classify)

    assert '{"tasks": [' in prompt
    assert '{"tasks": []}' in prompt


@pytest.mark.parametrize("classify", [False, True])
def test_the_cap_the_prompt_states_is_the_one_the_model_enforces(classify: bool) -> None:
    """Filled from MAX_TASKS_PER_TEXT, not written in: a fact in two files drifts."""

    prompt = system_prompt(classify)

    assert f"at most {MAX_TASKS_PER_TEXT} tasks" in prompt
    assert "{{" not in prompt


def test_the_examples_show_a_text_with_several_tasks() -> None:
    assert any(len(example["tasks"]) > 1 for example in _examples())


def test_a_repair_asks_for_every_task_again_and_unchanged() -> None:
    """Without it a model may return only the task it fixed, and a valid
    answer would silently lose the others: decision 3 of #690, undone."""

    prompt = repair_prompt(SourceType.EMAIL, "text", '{"tasks": []}', "tasks.1.title: ...")

    assert "every task" in prompt
    assert "unchanged" in prompt


# --- dependency_mentions (#690, was #842) ---------------------------------


@pytest.mark.parametrize("classify", [False, True])
def test_the_mentions_are_defined_beside_has_dependencies_in_both_modes(classify: bool) -> None:
    """A field of every task, so it cannot live in the block the switch removes."""

    prompt = system_prompt(classify)

    assert prompt.index("`has_dependencies`") < prompt.index("`dependency_mentions`")
    assert "`dependency_mentions` is not empty, `has_dependencies` is true" in prompt


def test_an_example_shows_one_task_naming_another_from_the_same_text() -> None:
    linked = [example for example in _examples() if len(example["tasks"]) > 1]

    assert any(
        task["dependency_mentions"] and task["has_dependencies"]
        for example in linked
        for task in example["tasks"]
    )


def test_the_repair_prompt_lists_no_keys_of_its_own() -> None:
    """It points at the instructions instead. Listing keys here once dropped
    three classification fields from every repaired answer (#587)."""

    prompt = repair_prompt(SourceType.EMAIL, "text", "{}", "error")

    for key in response_model(classify=True).model_fields:
        assert key not in prompt


def test_a_repair_may_drop_tasks_when_the_rejection_asks_for_it() -> None:
    """"Return every task again, unchanged" would otherwise forbid obeying a
    rejection for having too many."""

    prompt = repair_prompt(SourceType.MEETING, "text", "{}", "tasks: there are 17")

    assert "unless the rejection asks you to remove some" in prompt
