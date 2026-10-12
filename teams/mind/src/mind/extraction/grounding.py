"""Checking what the model claims against the content it read.

`source_ref` is a pointer back to the original item — a message id, a ticket
key, a URL — and `RawObjectEvent` carries no such field, so the only place it
can come from is inside the content itself. That makes the rule here a
definition rather than a restriction: a pointer that does not appear in the
content was not found there, it was invented.

Why it is worth a module of its own rather than a line in the scorer: the
field is what a user later clicks to see "the original", so what goes into
it is a boundary decision, not a scoring detail. The scorer is told what
happened afterwards, and only reads.

**What this does not do.** It closes fabrication — a model inventing a
pointer that was never in the content. It does not close injection. The
content is written by whoever sent it, so a sender can put a link in their
own message and tell the model to use it; the value then genuinely is in the
content and passes every check here. The only real answer to that is for
`source_ref` to arrive from Bridge as event metadata instead of being read
out of the body — `RawObjectEvent` carries no such field today, and asking
for one is open with them. Until it exists, treat this field as
content-derived: it says what the content claimed, not what is true.

This belongs between parsing and scoring because that is the one point where
both the raw text and the extracted fields are in hand: the schema never sees
the content, and the scorer should not be changing what it measures.

`confidence.title_grounding` does the same kind of overlap check for the
title, but for scoring rather than for dropping. Folding the two together is
a follow-up, not a change to make while fixing this.
"""

import logging
import re

from mind.extraction.models import ExtractedFields

logger = logging.getLogger("mind.extraction")

# Below this, a "pointer" matches by accident: a ref of "7" is inside almost
# any text that mentions a date.
MIN_SOURCE_REF_LENGTH = 4

# Appearing in the content proves where a value came from, not what kind of
# thing it is: "before Friday" would let `source_ref: "Friday"` through on
# provenance alone. Identifiers carry a digit, or one of the characters they
# are built out of; ordinary words carry neither.
_LOOKS_LIKE_A_POINTER = re.compile(r"[0-9@/#]|[A-Za-z]-[A-Za-z0-9]|:[0-9/]")

# Punctuation a model adds or strips around an identifier it copied correctly:
# <MSG-4471@host> and MSG-4471@host are the same pointer.
_WRAPPING = "<>[](){}\"'`,.;: \t\n"


def _comparable(value: str) -> str:
    """Lower-cased and stripped of the punctuation that wraps identifiers."""

    return value.strip(_WRAPPING).lower()


def ground_source_ref(
    fields: ExtractedFields,
    raw_text: str,
) -> tuple[ExtractedFields, bool]:
    """Keep `source_ref` only if the content actually contains it.

    Returns the fields — unchanged, or with `source_ref` dropped — and whether
    a pointer was dropped. The caller passes that flag to the scorer, because
    a model that invented a pointer is less trustworthy than one that honestly
    reported none, and the two must not end up scoring the same.

    Three things have to hold: the value appears in the content, it is long
    enough not to match by accident, and it is shaped like an identifier
    rather than like a word. Matching is deliberately shallow otherwise —
    case-insensitive and tolerant of wrapping punctuation, but it does not
    normalise what is *inside* the pointer. A looser match would start
    accepting things the content never said, which is the whole failure this
    exists to prevent.
    """

    claimed = fields.source_ref
    if claimed is None:
        # Nothing claimed is not an invention. It is the honest answer the
        # prompt asks for when the content carries no pointer.
        return fields, False

    needle = _comparable(claimed)
    quoted_from_content = needle in raw_text.lower()
    long_enough = len(needle) >= MIN_SOURCE_REF_LENGTH
    shaped_like_a_pointer = bool(_LOOKS_LIKE_A_POINTER.search(needle))

    if quoted_from_content and long_enough and shaped_like_a_pointer:
        return fields, False

    logger.warning(
        "extraction: source_ref %r does not appear in the content; dropping it. "
        "The field is shown to a user as the original item, so an unverifiable "
        "pointer must not travel",
        claimed,
    )
    return fields.model_copy(update={"source_ref": None}), True
