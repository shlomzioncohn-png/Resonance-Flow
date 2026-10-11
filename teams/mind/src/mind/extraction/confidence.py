"""Confidence scoring for extracted task fields (#350).

`confidence` must reflect something real, so it is derived from signals we
actually observe during extraction: how many attempts the LLM needed, how
much of the title is grounded in the source text, and which optional fields
came back empty. The full rationale and the weights live in README.md.

Signals are combined multiplicatively — each one removes a share of the
confidence still standing, not a fixed number of points. Our signals are
correlated (a thin source fires three of them at once), so charging each of
them full price would punish one underlying cause several times over.

The scorer is deliberately pure — no I/O, no LLM — so it is cheap to test
and cheap to reason about when a reviewer asks where a number came from.
"""

import re
from dataclasses import dataclass

from mind.contracts.base_enums import SourceType
from mind.extraction.models import ExtractedFields

# Starting point for a first-attempt extraction with everything present.
# Not 1.0: this stage is an ML-inferred proposal, never a certainty.
BASE_CONFIDENCE = 0.95

# Every weight below is a *share of the confidence still standing*, not a
# number of points, because the signals are combined multiplicatively.

# Charged per repair round, compounded: two rounds cost 1 - 0.8**2.
PENALTY_PER_REPAIR = 0.20

# Scaled by the share of title words absent from the source text, so a
# fully invented title costs the whole weight and a paraphrase costs little.
WEIGHT_TITLE_GROUNDING = 0.25

PENALTY_MISSING_DESCRIPTION = 0.10
PENALTY_MISSING_SOURCE_REF = 0.10

# Charged when the model produced a source_ref that the content never
# contained, so `grounding` dropped it. Heavier than simply not finding
# one: an honest null is a gap, an invented pointer is a fabrication, and
# the two must not score the same. It compounds with the missing-ref
# penalty, because after the drop the field is also genuinely missing.
PENALTY_INVENTED_SOURCE_REF = 0.20
PENALTY_SHORT_TITLE = 0.10

CONFIDENCE_FLOOR = 0.05
CONFIDENCE_CEILING = 0.99

# A title needs at least this many content words to count as informative.
MIN_TITLE_CONTENT_WORDS = 2

# Sources whose content normally carries a pointer back to the original
# (message id, URL, ticket key). A missing source_ref is only suspicious
# for these; for the others it is simply how the source works.
REFERENCE_BEARING_SOURCES = frozenset(
    {
        SourceType.EMAIL,
        SourceType.SLACK,
        SourceType.WHATSAPP,
        SourceType.CALENDAR,
        SourceType.DOC,
        SourceType.DRIVE,
        SourceType.GITHUB_ISSUE,
        SourceType.GITHUB_PR,
        SourceType.CRM_DEAL,
    }
)

# English only — Kayvan confirmed the pipeline assumes English content.
_STOPWORDS = frozenset(
    {
        "and", "are", "but", "for", "from", "has", "have", "into", "its", "not", "our",
        "that", "the", "their", "them", "then", "there", "this", "was", "were", "will",
        "with", "you", "your",
    }
)

_WORD = re.compile(r"[a-z0-9]+")


@dataclass(frozen=True)
class ConfidencePenalty:
    """One deduction, kept named so the score can be explained.

    `weight` is the share of the remaining confidence this signal removes;
    `effective` is what it actually cost here, in points, given the signals
    applied before it. The effective costs sum to the total drop.
    """

    reason: str
    weight: float
    effective: float


@dataclass(frozen=True)
class ConfidenceBreakdown:
    """The score plus every deduction that produced it."""

    base: float
    penalties: tuple[ConfidencePenalty, ...]
    value: float


def _content_words(text: str) -> list[str]:
    """Lowercase word tokens, minus stopwords and very short tokens."""

    return [
        word
        for word in _WORD.findall(text.lower())
        if len(word) >= 3 and word not in _STOPWORDS
    ]


def title_grounding(title: str, raw_text: str) -> float:
    """Share of the title's content words that appear in the source text.

    Returns 1.0 for a title with no content words: that case is already
    covered by the short-title penalty and should not be charged twice.
    """

    title_words = _content_words(title)
    if not title_words:
        return 1.0

    haystack = set(_WORD.findall(raw_text.lower()))
    return sum(1 for word in title_words if word in haystack) / len(title_words)


def _collect_signals(
    fields: ExtractedFields,
    source_type: SourceType,
    raw_text: str,
    attempts: int,
    invented_source_ref: bool,
) -> list[tuple[str, float]]:
    """Name every weakness observed in this extraction, with its weight."""

    signals: list[tuple[str, float]] = []

    repairs = max(0, attempts - 1)
    if repairs:
        # Compounded rather than multiplied, so the weight stays below 1
        # no matter how many rounds were spent.
        signals.append(
            ("repair_attempts", 1.0 - (1.0 - PENALTY_PER_REPAIR) ** repairs)
        )

    ungrounded = 1.0 - title_grounding(fields.title, raw_text)
    if ungrounded > 0:
        signals.append(("title_not_grounded", WEIGHT_TITLE_GROUNDING * ungrounded))

    if len(_content_words(fields.title)) < MIN_TITLE_CONTENT_WORDS:
        signals.append(("short_title", PENALTY_SHORT_TITLE))

    if fields.description is None:
        signals.append(("missing_description", PENALTY_MISSING_DESCRIPTION))

    if invented_source_ref:
        signals.append(("invented_source_ref", PENALTY_INVENTED_SOURCE_REF))

    if fields.source_ref is None and source_type in REFERENCE_BEARING_SOURCES:
        signals.append(("missing_source_ref", PENALTY_MISSING_SOURCE_REF))

    return signals


def score_confidence(
    fields: ExtractedFields,
    source_type: SourceType,
    raw_text: str,
    attempts: int = 1,
    invented_source_ref: bool = False,
) -> ConfidenceBreakdown:
    """Score one extraction, keeping each deduction addressable.

    `attempts` counts the rounds spent getting the schema right, so 1 means
    the first response validated and anything above it means a repair round
    was needed. Provider failures are not counted here: a timeout says
    nothing about the quality of the answer that eventually arrived.

    `invented_source_ref` is reported by `grounding`, which has already
    dropped the offending value. This scorer only reads.

    The signals are applied in sequence, each taking its share of what is
    left. The final value does not depend on that order — multiplication
    commutes — but the per-signal `effective` cost does, so the order in
    `_collect_signals` is fixed and deliberate: strongest evidence first.
    """

    penalties: list[ConfidencePenalty] = []
    value = BASE_CONFIDENCE

    for reason, weight in _collect_signals(
        fields, source_type, raw_text, attempts, invented_source_ref
    ):
        remaining = value * (1.0 - weight)
        penalties.append(
            ConfidencePenalty(
                reason=reason,
                weight=round(weight, 4),
                effective=round(value - remaining, 4),
            )
        )
        value = remaining

    value = min(CONFIDENCE_CEILING, max(CONFIDENCE_FLOOR, value))

    return ConfidenceBreakdown(
        base=BASE_CONFIDENCE,
        penalties=tuple(penalties),
        value=round(value, 4),
    )


def compute_confidence(
    fields: ExtractedFields,
    source_type: SourceType,
    raw_text: str,
    attempts: int = 1,
    invented_source_ref: bool = False,
) -> float:
    """Convenience wrapper for callers that only need the number."""

    return score_confidence(
        fields, source_type, raw_text, attempts, invented_source_ref
    ).value
