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

from mind.extraction.models import ExtractedFields


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
) -> ExtractedFields:
    """Read one answer into `schema`, or raise `MalformedOutput`.

    `schema` is which shape was asked for: the four basic fields, or the
    seven that include the classification. It defaults to the basic one so
    that every existing caller keeps the behaviour it had.

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
        return schema.model_validate(payload)
    except ValidationError as exc:
        raise MalformedOutput(describe_validation_error(exc)) from exc
