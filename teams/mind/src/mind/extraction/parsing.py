"""Turning an LLM's answer into validated fields, or into a clear rejection.

The guiding line here: salvage *formatting*, never salvage *content*. A model
that wrapped its JSON in a code fence made a presentation mistake and costs us
nothing to forgive. A model that omitted `title` made a content mistake, and
quietly filling it in would hide exactly the failure this stage exists to
catch.
"""

import json
import logging
import re
from dataclasses import dataclass

from pydantic import ValidationError

from mind.extraction.models import ExtractedFields, ExtractedTasks

logger = logging.getLogger("mind.extraction")


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

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end < start:
        raise MalformedOutput("the answer contains no JSON object")
    return text[start : end + 1]


def _read_payload(text: str) -> object:
    """The answer's JSON, with the formatting slips forgiven, or `MalformedOutput`."""

    if not text or not text.strip():
        raise MalformedOutput("the answer was empty")

    body = _strip_code_fence(text)

    # The list without the object around it is the right answer written
    # wrong, like a code fence: wrapped here rather than repaired, which would
    # cost a call and charge every task in it. Narrowing it to { ... } instead
    # would cut out `{task}, {task}` and call valid JSON invalid. When it does
    # not read as a list, it was prose that happens to open with a bracket.
    if body.startswith("["):
        try:
            tasks = json.loads(body)
        except json.JSONDecodeError:
            pass
        else:
            logger.info("extraction: the answer was a bare list; reading it as the tasks")
            return {"tasks": tasks}

    try:
        return json.loads(_first_json_object(body))
    except json.JSONDecodeError as exc:
        raise MalformedOutput(f"the answer is not valid JSON ({exc.msg})") from exc


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

    payload = _read_payload(text)

    try:
        # mypy reads `ExtractedTasks[schema]` as a type annotation, where a
        # variable is not allowed. Here it is a runtime call: pydantic builds
        # the list model for whichever shape `schema` holds.
        envelope = ExtractedTasks[schema]  # type: ignore[valid-type]
        return envelope.model_validate(payload).tasks
    except ValidationError as exc:
        raise MalformedOutput(describe_validation_error(exc)) from exc


@dataclass(frozen=True)
class RejectedAnswer:
    """What can still be read from an answer that failed validation."""

    # How many entries its `tasks` list held, valid or not; 0 when unreadable.
    task_count: int
    # The entries that pass the task schema each on its own.
    valid: list[ExtractedFields]


def read_rejected_answer(
    text: str,
    schema: type[ExtractedFields] = ExtractedFields,
) -> RejectedAnswer:
    """How many tasks a rejected answer held, and which pass `schema` on their own.

    Used for scoring and never as output, so it does not break the rule at the
    top of this module: nothing found here reaches Inbox. The extractor only
    asks whether a task in the final answer was already valid in an earlier
    round, so that a repair round is charged to the task that needed it.

    Never raises. It runs on an answer that has already failed, so any shape
    can arrive here, and an exception would end the run instead of the item.
    """

    try:
        payload = _read_payload(text)
    except MalformedOutput:
        return RejectedAnswer(task_count=0, valid=[])

    tasks = payload.get("tasks") if isinstance(payload, dict) else None
    if not isinstance(tasks, list):
        return RejectedAnswer(task_count=0, valid=[])

    found = []
    for task in tasks:
        try:
            found.append(schema.model_validate(task))
        except ValidationError:
            continue
    return RejectedAnswer(task_count=len(tasks), valid=found)
