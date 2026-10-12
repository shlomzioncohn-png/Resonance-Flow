"""Loading and filling the extraction prompts.

The prompts live next to this module as plain `.md` files rather than as
string literals: they change far more often than the logic around them, and
keeping them separate means a prompt change shows up in review as a prompt
change.
"""

import re
from functools import cache
from pathlib import Path

from mind.contracts.base_enums import SourceType

_PROMPT_DIR = Path(__file__).parent / "prompts"

# Content longer than this is truncated before it reaches the provider.
# Tokens cost money and a task rarely hides in the tail of a long thread;
# the marker keeps the truncation visible to whoever reads the prompt.
MAX_CONTENT_CHARS = 8000
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


def system_prompt() -> str:
    """The instructions shared by the first attempt and every repair."""

    return _load("system.md")


def extraction_prompt(source_type: SourceType, content: str) -> str:
    """The first-attempt user message."""

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

    return _fill(
        _load("repair_user.md"),
        {
            "SOURCE_TYPE": source_type.value,
            "CONTENT": truncate(content),
            "INVALID_OUTPUT": truncate(invalid_output),
            "ERROR": error,
        },
    )
