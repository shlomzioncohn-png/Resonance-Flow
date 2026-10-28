"""Checking what the model claims against the content it read.

`source_ref` is a pointer the model says it read in the content — a message
id, a ticket key, a URL. It is **not** what reaches Inbox: the `source_ref`
sent there comes from Bridge's event (#31, #353), never from the model, as
`assembly.source_ref_for_inbox` says. What the model wrote is still checked,
because inventing a pointer says something about the answer: a pointer that
does not appear in the content was not found there, it was invented, and
`ground_source_ref` drops it and tells the scorer (`invented_source_ref`), which
lowers the task's `confidence`.

**What this does not do.** It closes fabrication — a model inventing a
pointer that was never in the content. It does not close injection: a sender
can put a link in their own message and tell the model to use it, and the value
then genuinely is in the content. That no longer reaches Inbox, since Bridge's
own `source_ref` is the one sent.

This belongs between parsing and scoring because that is the one point where
both the raw text and the extracted fields are in hand: the schema never sees
the content, and the scorer should not be changing what it measures.

**`dependency_mentions` (#690)** are checked here too, by
`ground_dependency_mentions`, for the same reason and more loosely: a mention
is a paraphrase of other work, not an identifier. That function is also where
a grounded mention turns `has_dependencies` true, because only here is the
content in hand to tell a real mention from an invented one.

**The deadline's phrase (#960)** is checked by `ground_deadline`, the same
way as a mention and then by its numbers too.

**`owner_mention` (#132)** is checked by `ground_owner_mention`, more strictly
than a mention: a name is not a paraphrase, so none of its words may be
missing, and they must stand together.

`confidence.title_grounding` does the same kind of overlap check for the
title, but for scoring rather than for dropping. Folding the two together is
a follow-up, not a change to make while fixing this.
"""

import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass

from mind.extraction.confidence import content_words
from mind.extraction.models import ClassificationFields, ExtractedFields

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


# A mention keeps its place when every one of its content words is in the
# content, except that one may be missing from a mention of at least
# MIN_WORDS_TO_MISS_ONE: the model paraphrases ("the updated cost sheet" for
# "the cost sheet"). Not a share: in a long meeting "review" or "budget" is
# said somewhere, so half of a two-word mention grounds almost anything. Not
# calibrated; the persona set has no labelled mentions to tune on.
MIN_WORDS_TO_MISS_ONE = 3

# Whole numbers, so "72" is not found inside "720".
_NUMBER = re.compile(r"[0-9]+")


def _numbers(text: str) -> set[int]:
    # As values, so the 9 of "by 9:00" is the 09 of a text that writes 09:00.
    return {int(digits) for digits in _NUMBER.findall(text)}


@dataclass(frozen=True)
class Said:
    """The content's words and numbers, read once per item.

    Every task of an item is checked against the same text, three times
    (mentions, owner, deadline): a meeting of fifteen tasks used to read its
    100k characters thirty times. The extractor reads it once and hands this
    to each task's checks; it lives as long as that one item's extraction.

    `name_words` is for the owner: every word in order, short ones too, one
    run of words between two email addresses (see `_written_together`).
    """

    words: frozenset[str]
    numbers: frozenset[int]
    name_words: tuple[tuple[str, ...], ...]


def said_in(raw_text: str) -> Said:
    return Said(
        frozenset(content_words(raw_text)),
        frozenset(_numbers(raw_text)),
        tuple(tuple(_name_words(stretch)) for stretch in _ADDRESS.split(raw_text)),
    )


def _words_said(words: list[str], said: frozenset[str]) -> bool:
    """The rule for a paraphrase: every content word said, except that one may
    be missing from MIN_WORDS_TO_MISS_ONE words up. Shared by mentions and the
    deadline's phrase, so tightening it changes both."""

    missing = sum(1 for word in words if word not in said)
    return missing <= (1 if len(words) >= MIN_WORDS_TO_MISS_ONE else 0)


def ground_dependency_mentions(
    fields: ExtractedFields,
    raw_text: str,
    sibling_titles: Iterable[str] = (),
    said: Said | None = None,
) -> ExtractedFields:
    """Keep the mentions the content names, then let only those flip the flag.

    The same reason as `ground_source_ref`: the content is untrusted and the
    model invents, so a phrase naming work the content never mentions does
    not travel to #834's resolver. Shallower than for a pointer, because a
    mention is a paraphrase, not an identifier: most of its content words
    must be in the content (see MIN_WORDS_TO_MISS_ONE), and a mention with
    none ("it") names nothing. A longer mention is not easier to pass: every
    word it adds is one more that must be in the content, so context the
    model made up costs it the whole mention.

    A mention equal to the title of another task in the same answer is
    grounded by that task: the prompt asks for the sibling's title, and a
    title is the model's own summary, so its words need not all be said.

    Whether the words sit near each other is not checked: "security" early in
    a meeting and "review" late still ground "the security review". Closing
    that needs a proximity check, worth it only if live runs show it matters.

    The rule "a mention means a dependency" is applied here, after the check,
    and not in the model: there an invented mention would already have turned
    a `false` into a `true` before anything could see that it was invented.
    A `true` the model wrote itself stays, mentions or not: the content may
    show other work without naming it.
    """

    words_said = (said or said_in(raw_text)).words
    siblings = {title.strip().lower() for title in sibling_titles}
    kept = []
    for mention in fields.dependency_mentions:
        words = content_words(mention)
        if mention.strip().lower() in siblings or (words and _words_said(words, words_said)):
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


# A name's words: letters and digits, every one of them kept. Not
# `content_words`, which drops words under three letters and would read
# "Dana C." as "Dana" and "Li Wu" as nothing.
_NAME_WORD = re.compile(r"[^\W_]+")

# An email address in the content. Its words do not name anyone: "Dana" is
# not written by dana.cohen@acme.test, and the recipient's address comes from
# the email record, not from a name grounded here (#132). It starts only where
# a run of address characters starts: tried from every character, a long run
# with no "@" (base64 in a Gmail message) took quadratic time, 25 s at 100k.
_ADDRESS = re.compile(r"(?<![\w.%+-])[\w.%+-]+@[\w-]+(?:\.[\w-]+)*")


def _name_words(text: str) -> list[str]:
    return _NAME_WORD.findall(text.lower())


def _written_together(name: str, said: Said) -> bool:
    """Every word of the name, side by side in the content, in either order:
    "Cohen, Dana" writes "Dana Cohen"; "Dana Levi and Moshe Cohen" does not.

    Each stretch between two addresses is read on its own, so the words on
    either side of an address never stand together.
    """

    wanted = sorted(_name_words(name))
    if not wanted:
        return False
    size = len(wanted)
    return any(
        sorted(words[i : i + size]) == wanted
        for words in said.name_words
        for i in range(len(words) - size + 1)
    )


def ground_owner_mention(
    fields: ExtractedFields, raw_text: str, said: Said | None = None
) -> ExtractedFields:
    """Keep the owner only if the content writes the name; otherwise there is none.

    Strict because a wrong owner costs more than none: Inbox rejects the whole
    task for an id it does not know, and a guessed id names the wrong person.
    It closes invention, not the sender: a sender who signs their name has
    written it, so only the prompt keeps them out. Logged without the name.
    """

    claimed = fields.owner_mention
    if claimed is None:
        return fields
    if _written_together(claimed, said or said_in(raw_text)):
        return fields
    logger.warning("extraction: owner_mention does not appear in the content; recording no owner")
    return fields.model_copy(update={"owner_mention": None})


def ground_deadline(
    fields: ExtractedFields, raw_text: str, said: Said | None = None
) -> ExtractedFields:
    """Keep the deadline only if the content says its phrase.

    The words follow the rule for a mention (MIN_WORDS_TO_MISS_ONE). The
    numbers are checked on their own and none may be missing: the word check
    drops anything shorter than three characters, which is most of a deadline
    ("72", "20", "14:00"), and the number is the answer. A phrase with no word
    and no number ("by then") points at nothing and is dropped too.

    It catches an invented deadline, not a misattributed one: the "March" of
    another event the content mentions is really there and passes.

    The log names no phrase: it is the content's own words.
    """

    if not isinstance(fields, ClassificationFields) or fields.deadline is None:
        return fields

    phrase = fields.deadline.phrase
    words = content_words(phrase)
    numbers = _numbers(phrase)
    said = said or said_in(raw_text)

    if (words or numbers) and _words_said(words, said.words) and numbers <= said.numbers:
        return fields
    logger.warning(
        "extraction: deadline phrase (%d words, %d numbers) does not appear in the content; "
        "recording the deadline as null",
        len(words),
        len(numbers),
    )
    return fields.model_copy(update={"deadline": None})
