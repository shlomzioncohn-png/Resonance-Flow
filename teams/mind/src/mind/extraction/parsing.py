"""Turning an LLM's answer into validated fields, or into a clear rejection.

The guiding line here: salvage *formatting*, never salvage *content*. A model
that wrapped its JSON in a code fence made a presentation mistake and costs us
nothing to forgive. A model that omitted `title` made a content mistake, and
quietly filling it in would hide exactly the failure this stage exists to
catch.
"""

import json
import re

from pydantic import ValidationError

from mind.extraction.models import ExtractedFields, ExtractedTasks


class MalformedOutput(Exception):
    """The answer could not be read as the fields we asked for.

    The message is written to be handed straight back to the model in the
    repair prompt, so it says what is wrong rather than where the code broke.
    """


# ```json ... ``` or ``` ... ``` around the whole answer.
_CODE_FENCE = re.compile(r"^\s*```(?:json)?\s*(?P<body>.*?)\s*```\s*$", re.DOTALL)


def _strip_code_fence(text: str) -> str:
    """Remove a markdown fence around the answer, if the model added one."""

    match = _CODE_FENCE.match(text)
    return match.group("body") if match else text.strip()


def _first_json_object(text: str) -> str:
    """Narrow to the outermost { ... } span, for answers with stray prose."""

    # A model asked for a list of tasks may return the list itself and skip
    # the object around it. Narrowing that to { ... } would cut out
    # `{task}, {task}` and report "not valid JSON" about valid JSON, which
    # sends the repair round looking for a syntax error that is not there.
    if text.startswith("["):
        raise MalformedOutput(
            'the answer is a list, not the object; put the tasks inside {"tasks": [...]}'
        )

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end < start:
        raise MalformedOutput("the answer contains no JSON object")
    return text[start : end + 1]


def describe_validation_error(error: ValidationError) -> str:
    """Condense a pydantic error into something a model can act on."""

    parts = []
    for detail in error.errors():
        location = ".".join(str(item) for item in detail["loc"]) or "(root)"
        parts.append(f"{location}: {detail['msg']}")
    return "; ".join(parts)


def parse_response(
    text: str,
    schema: type[ExtractedFields] = ExtractedFields,
) -> list[ExtractedFields]:
    """Read one answer into a list of `schema`, or raise `MalformedOutput`.

    The answer is `{"tasks": [...]}` (#690), and `schema` is the shape of each
    task: the basic fields alone, or with the classification and effort
    estimate. The list may be empty; one task that fails `schema` fails the
    whole answer, and the message names it by its place (`tasks.1.title`).

    Every rejection path raises the same exception type: for the extractor a
    failed parse and a failed validation are the same normal event, and both
    lead to the same repair round.
    """

    if not text or not text.strip():
        raise MalformedOutput("the answer was empty")

    candidate = _first_json_object(_strip_code_fence(text))

    try:
        payload = json.loads(candidate)
    except json.JSONDecodeError as exc:
        raise MalformedOutput(f"the answer is not valid JSON ({exc.msg})") from exc

    # No isinstance check on `payload`: it was narrowed to the outermost
    # { ... } span above, so anything json.loads accepts here is an object.

    try:
        # mypy reads `ExtractedTasks[schema]` as a type annotation, where a
        # variable is not allowed. Here it is a runtime call: pydantic builds
        # the list model for whichever shape `schema` holds.
        envelope = ExtractedTasks[schema]  # type: ignore[valid-type]
        return envelope.model_validate(payload).tasks
    except ValidationError as exc:
        raise MalformedOutput(describe_validation_error(exc)) from exc


def valid_tasks(
    text: str,
    schema: type[ExtractedFields] = ExtractedFields,
) -> list[ExtractedFields]:
    """The tasks in a rejected answer that pass `schema` each on its own.

    Used for scoring and never as output, so it does not break the rule at the
    top of this module: nothing found here reaches Inbox. The extractor only
    asks whether a task in the final answer was already valid in an earlier
    round, so that a repair round is charged to the task that needed it.

    Never raises. It runs on an answer that has already failed, so any shape
    can arrive here, and an exception would end the run instead of the item.
    """

    try:
        payload = json.loads(_first_json_object(_strip_code_fence(text)))
    except (MalformedOutput, json.JSONDecodeError):
        return []

    tasks = payload.get("tasks") if isinstance(payload, dict) else None
    if not isinstance(tasks, list):
        return []

    found = []
    for task in tasks:
        try:
            found.append(schema.model_validate(task))
        except ValidationError:
            continue
    return found
