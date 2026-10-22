"""Checking what the model claims against the content it read.

`source_ref` is a pointer back to the original item — a message id, a ticket
key, a URL. Bridge now sends one on `RawObjectEvent` (#31), but extraction
does not receive the event yet, so here the only place it can come from is
inside the content itself. That makes the rule here a
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
out of the body. #31 settled that: `RawObjectEvent.source_ref`. Once the
consumer passes it through, it should replace this content-derived value.
Until then, treat this field as content-derived: it says what the content
claimed, not what is true.

This belongs between parsing and scoring because that is the one point where
both the raw text and the extracted fields are in hand: the schema never sees
the content, and the scorer should not be changing what it measures.

`confidence.title_grounding` does the same kind of overlap check for the
title, but for scoring rather than for dropping. Folding the two together is
a follow-up, not a change to make while fixing this.
"""

import logging
import re

from mind.extraction.confidence import content_words, title_grounding
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


# A mention keeps its place when at least this share of its content words is
# in the content. Not all: the model paraphrases ("the updated cost sheet" for
# "the cost sheet"), and a mention only has to find the other task, not quote
# it. Not calibrated; the persona set has no labelled mentions to tune on.
MIN_MENTION_GROUNDING = 0.5


def ground_dependency_mentions(fields: ExtractedFields, raw_text: str) -> ExtractedFields:
    """Keep the mentions the content names, then let only those flip the flag.

    The same reason as `ground_source_ref`: the content is untrusted and the
    model invents, so a phrase naming work the content never mentions does
    not travel to #834's resolver. Shallower than for a pointer, because a
    mention is a paraphrase, not an identifier: most of its content words
    must be in the content, and a mention with none ("it") names nothing.

    The rule "a mention means a dependency" is applied here, after the check,
    and not in the model: there an invented mention would already have turned
    a `false` into a `true` before anything could see that it was invented.
    A `true` the model wrote itself stays, mentions or not: the content may
    show other work without naming it.
    """

    kept = []
    for mention in fields.dependency_mentions:
        if content_words(mention) and title_grounding(mention, raw_text) >= MIN_MENTION_GROUNDING:
            kept.append(mention)
        else:
            logger.warning(
                "extraction: dependency mention %r does not appear in the content; dropping it",
                mention,
            )

    has_dependencies = fields.has_dependencies
    if kept and not has_dependencies:
        logger.warning(
            "extraction: dependency_mentions named %s but has_dependencies was false; "
            "recording it as true",
            kept,
        )
        has_dependencies = True

    if kept == fields.dependency_mentions and has_dependencies == fields.has_dependencies:
        return fields
    return fields.model_copy(
        update={"dependency_mentions": kept, "has_dependencies": has_dependencies}
    )
