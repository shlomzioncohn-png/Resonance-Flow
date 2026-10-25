"""Loading and filling the extraction prompts.

The prompts live next to this module as plain `.md` files rather than as
string literals: they change far more often than the logic around them, and
keeping them separate means a prompt change shows up in review as a prompt
change.
"""

import logging
import re
from functools import cache
from pathlib import Path

from mind.contracts.base_enums import SourceType
from mind.extraction.models import MAX_TASKS_PER_TEXT

logger = logging.getLogger("mind.extraction")

_PROMPT_DIR = Path(__file__).parent / "prompts"

# Content longer than this is truncated before it reaches the provider.
# Tokens cost money and a task rarely hides in the tail of a long thread;
# the marker keeps the truncation visible to whoever reads the prompt.
MAX_CONTENT_CHARS = 8000
TRUNCATION_MARKER = "\n[... content truncated ...]"

# A meeting is the exception (#690): its action items come in the closing
# recap, so cutting its tail cuts exactly them. Live on AMI, cut at 8000 the
# two meetings found 0/4 and 0/8 gold action items, sent whole 4/4 and 4/8.
# Their transcripts are 26k and 35k characters; this leaves room for an hour.
# About 15k tokens at most, under a cent a meeting at today's price.
MAX_MEETING_CONTENT_CHARS = 60000


def content_cap(source_type: SourceType) -> int:
    """How much of an item's content is sent to the model."""

    return MAX_MEETING_CONTENT_CHARS if source_type is SourceType.MEETING else MAX_CONTENT_CHARS


# Placeholders are {{NAME}} and are filled by substitution, not by
# str.format: the prompts contain JSON braces, and format() would choke on
# them or, worse, silently reinterpret them.
_PLACEHOLDER = re.compile(r"\{\{[A-Z_]+\}\}")


@cache
def _load(name: str) -> str:
    """Read one prompt file, cached for the life of the process."""

    return (_PROMPT_DIR / name).read_text(encoding="utf-8").strip()


def truncate(content: str, limit: int = MAX_CONTENT_CHARS) -> str:
    """Cap the content sent to a provider, marking where it was cut."""

    if len(content) <= limit:
        return content
    return content[:limit] + TRUNCATION_MARKER


def _fill(template: str, values: dict[str, str]) -> str:
    """Substitute every {{PLACEHOLDER}}, refusing to leave one behind."""

    filled = template
    for key, value in values.items():
        filled = filled.replace("{{" + key + "}}", value)

    leftover = _PLACEHOLDER.findall(filled)
    if leftover:
        raise ValueError(f"unfilled placeholders in prompt: {sorted(set(leftover))}")

    return filled


def system_prompt(classify: bool = False) -> str:
    """The instructions shared by the first attempt and every repair.

    `classify` appends the #351 block asking for tier, urgency and
    role_context. The two live in separate files and are joined here
    rather than being one file with a switch inside it, so that turning
    classification off leaves a prompt that never mentions it — a model
    cannot volunteer a key it was never told about.

    It is also what makes the fallback cheap: if asking for everything in
    one call ever measures worse than two calls, this file is already the
    second call's system prompt, unchanged.
    """

    # The cap is filled in from the model that enforces it, so the number the
    # model is told and the number it is held to cannot drift apart.
    system = _fill(_load("system.md"), {"MAX_TASKS": str(MAX_TASKS_PER_TEXT)})
    if not classify:
        return system

    return system + "\n\n" + _load("classification.md")


def extraction_prompt(source_type: SourceType, content: str) -> str:
    """The first-attempt user message."""

    # The marker tells the model; this tells whoever reads the run. "A task
    # rarely hides in the tail" holds for a thread, not for a meeting, whose
    # action items come in the closing recap (#690). Here and not in
    # truncate(), which also runs on every repair round and on the model's
    # own answer: once per item, about the content.
    cap = content_cap(source_type)
    if len(content) > cap:
        logger.warning(
            "extraction: %s content of %d characters truncated to %d; the last %d are not sent",
            source_type.value,
            len(content),
            cap,
            len(content) - cap,
        )

    return _fill(
        _load("extraction_user.md"),
        {"SOURCE_TYPE": source_type.value, "CONTENT": truncate(content, cap)},
    )


def repair_prompt(
    source_type: SourceType,
    content: str,
    invalid_output: str,
    error: str,
) -> str:
    """The follow-up user message after an answer failed validation.

    The rejected answer and the reason both go back to the model: without
    them it has no way to know what to change, and a bare "try again" mostly
    produces the same broken answer a second time.
    """

    # The rejected answer gets the content's cap too: a meeting's list of
    # tasks runs past 8000 characters, and a repair asked to return every
    # task unchanged cannot copy the ones it is not shown.
    cap = content_cap(source_type)
    return _fill(
        _load("repair_user.md"),
        {
            "SOURCE_TYPE": source_type.value,
            "CONTENT": truncate(content, cap),
            "INVALID_OUTPUT": truncate(invalid_output, cap),
            "ERROR": error,
        },
    )
