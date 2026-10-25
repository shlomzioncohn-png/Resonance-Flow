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

# Content longer than this is truncated before it reaches the provider; the
# marker keeps the truncation visible to whoever reads the prompt. A safety
# net against a runaway item (a 500-page scan), not a saving: a short item is
# sent whole either way, and a cut loses what comes last. In a meeting that is
# its closing recap, where the action items are (#690: on AMI, cut at 8000 the
# two meetings found 0/4 and 0/8 gold items, sent whole 4/4 and 4/8). A Bridge
# item's parts share it before they reach here, the longest cut first, so its
# attachment is not the part lost (ingestion/extraction_items.py). One cap for
# every source, and only an item this long pays it: about 25k tokens, ~$0.019 a call
# today and twice that from 2027, again on every repair round, which resends
# the content. Past the monitoring's $0.02 a call (config.py), set when this
# cap was 8000, so such an item is flagged there.
MAX_CONTENT_CHARS = 100_000
TRUNCATION_MARKER = "\n[... content truncated ...]"


# Placeholders are {{NAME}} and are filled by substitution, not by
# str.format: the prompts contain JSON braces, and format() would choke on
# them or, worse, silently reinterpret them.
_PLACEHOLDER = re.compile(r"\{\{[A-Z_]+\}\}")


@cache
def _load(name: str) -> str:
    """Read one prompt file, cached for the life of the process."""

    return (_PROMPT_DIR / name).read_text(encoding="utf-8").strip()


def truncate(content: str) -> str:
    """Cap the content sent to a provider, marking where it was cut."""

    if len(content) <= MAX_CONTENT_CHARS:
        return content
    return content[:MAX_CONTENT_CHARS] + TRUNCATION_MARKER


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

    # The marker tells the model; this tells whoever reads the run. Here and
    # not in truncate(), which also runs on every repair round and on the
    # model's own answer: once per item, about the content.
    if len(content) > MAX_CONTENT_CHARS:
        logger.warning(
            "extraction: %s content of %d characters truncated to %d; the last %d are not sent",
            source_type.value,
            len(content),
            MAX_CONTENT_CHARS,
            len(content) - MAX_CONTENT_CHARS,
        )

    return _fill(
        _load("extraction_user.md"),
        {"SOURCE_TYPE": source_type.value, "CONTENT": truncate(content)},
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
    return _fill(
        _load("repair_user.md"),
        {
            "SOURCE_TYPE": source_type.value,
            "CONTENT": truncate(content),
            "INVALID_OUTPUT": truncate(invalid_output),
            "ERROR": error,
        },
    )
