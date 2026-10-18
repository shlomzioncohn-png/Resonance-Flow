"""The extraction-quality eval: precision/recall of extracted action items vs. annotations.

This is MIND's *second* harness. The ranking harness (`harness.py`) answers "given
a list of tasks, did the model rank the right one first?". This one answers a
question one step upstream: "did the pipeline extract the right action items from
a meeting *at all*?" — precision/recall against a human-annotated reference (AMI's
tagged action items; see `extraction_eval_design.md`).

`evaluate_extraction(extracted, annotated, *, similarity, threshold)` is the entry
point. It implements the matching rule locked in `extraction_eval_design.md` §3:

1. **Normalize** every string on both sides (`_normalize`) so the score never
   depends on casing or whitespace.
2. **Score** every (extracted, annotated) pair with the caller-supplied
   `similarity` function — a float in [0, 1], 1.0 = same commitment.
3. **Match** greedily, one-to-one: sort all pairs by similarity descending, walk
   the list, take a pair iff neither side is already matched *and* the score is
   `>= threshold`.
4. `TP` = matched pairs, `FP` = unmatched extracted items, `FN` = unmatched
   annotations.

For real pipeline output, call `evaluate_extracted_items(items, annotated, ...)`
instead: it takes `ExtractedItem`s (`extraction_contract.py`), so the extracted
side has to go through the contract before it is scored. `evaluate_extraction`
stays the string-level engine underneath it (the CLI and the fixture use it).

`similarity` and `threshold` are **parameters, never hardcoded** (issue
requirement): when Ayala's embedding-similarity function lands it is swapped in at
the call site with no change here, and the locked lexical threshold
(`extraction_eval_design.md` §6) can be revised as a call-site change with a
visible paper trail rather than a silent code edit.

This matching is **inherently approximate** — it compares free text to free text
through a similarity function and a threshold that are judgement calls, not ground
truth. It is built to catch regressions and large differences between extraction
approaches on a fixed reference, not to certify an absolute quality score. See
`extraction_eval_design.md` §7.
"""

import numbers
from collections.abc import Callable, Sequence

from pydantic import BaseModel, ConfigDict, Field, computed_field

from mind.evaluation.extraction_contract import ExtractedItem


class MatchedPair(BaseModel):
    """One extracted item matched to one annotation, with the similarity that won it.

    Kept in the report (not just the count) so later weeks can explain *why* a run
    scored the way it did — the same "keep the raw evidence" requirement the
    ranking harness's `ScenarioResult.candidates` exists for.
    """

    model_config = ConfigDict(frozen=True)

    extracted: str
    annotated: str
    similarity: float = Field(ge=0.0, le=1.0)


class ExtractionEvalReport(BaseModel):
    """Aggregate result of one extraction-quality comparison.

    - `n_extracted` / `n_annotated`: how many items each side had — the
      denominators of precision and recall.
    - `precision`: `TP / len(extracted)` — of what the pipeline extracted, how much
      was real. `1.0` when nothing was extracted *and* nothing was annotated,
      `0.0` when nothing was extracted but annotations exist (matches the
      edge-case convention of `metrics.extraction_precision_recall`).
    - `recall`: `TP / len(annotated)` — of the real action items, how many were
      found. `1.0` when there was nothing to find.
    - `f1`: harmonic mean of precision and recall; `0.0` when either is `0.0`.
    - `matched`: the true-positive pairs, each with its similarity score.
    - `missed`: annotations that nothing matched — the false negatives.
    - `false_positives`: extracted items that matched nothing.

    `len(matched) == true_positives`, `len(false_positives) == FP`,
    `len(missed) == FN`. `true_positives` is a computed field, so it is part of
    `model_dump()` / the JSON report without being stored twice.
    """

    model_config = ConfigDict(frozen=True)

    n_extracted: int = Field(ge=0)
    n_annotated: int = Field(ge=0)
    precision: float = Field(ge=0.0, le=1.0)
    recall: float = Field(ge=0.0, le=1.0)
    f1: float = Field(ge=0.0, le=1.0)
    matched: list[MatchedPair] = Field(default_factory=list)
    missed: list[str] = Field(default_factory=list)
    false_positives: list[str] = Field(default_factory=list)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def true_positives(self) -> int:
        return len(self.matched)


def _normalize(text: str) -> str:
    """Text normalization applied to both sides before `similarity` is called.

    Locked in `extraction_eval_design.md` §3.2: lowercase; strip leading/trailing
    whitespace; collapse internal runs of whitespace to one space; strip a single
    trailing period. Nothing else — internal punctuation stays, and any stemming
    or stopword handling belongs to the `similarity` implementation, not here.
    """
    collapsed = " ".join(text.split())
    return collapsed.lower().removesuffix(".")


def _checked_unit_interval(value: object, what: str) -> float:
    """Return `value` as a float, or raise `ValueError` if it is not a real number in [0, 1].

    `similarity` is caller-supplied and `threshold` is a call-site knob, so neither is
    trusted. The check matters most for NaN: `nan < threshold` is `False`, so a NaN
    score slips past the matching loop's early `break` and is matched as if it had
    cleared the bar — the eval would report a perfect score instead of failing.
    `bool` is rejected too (`True` is an `int`, but is never a similarity).
    """
    number: float | None = None
    if isinstance(value, numbers.Real) and not isinstance(value, bool):
        number = float(value)
    if number is None or not 0.0 <= number <= 1.0:
        raise ValueError(f"{what} must be a real number in [0, 1]; got {value!r}")
    return number


# Threshold calibrated for `placeholder_similarity` (Jaccard). Not the locked lexical
# tau = 0.60 of `extraction_eval_design.md` §6, which belongs to the `rapidfuzz` backend.
PLACEHOLDER_THRESHOLD = 0.5


def placeholder_similarity(a: str, b: str) -> float:
    """Placeholder lexical similarity: Jaccard overlap of the two token sets.

    Pure-Python, no dependencies. This is **not** the backend the design calls
    for — the real eval uses either Ayala's sentence embeddings or a
    `rapidfuzz.fuzz.token_set_ratio` baseline (`extraction_eval_design.md` §4),
    neither of which is built yet (the baseline is blocked on Ayala's answer per
    issue step 8). It exists only so the fixture and the CLI have something
    runnable now — the "dummy model before the real model" bootstrap, same as the
    ranking harness.

    Inputs are assumed already `_normalize`d (as `evaluate_extraction` passes
    them). Two empty strings score 1.0; one empty and one not scores 0.0.
    """
    tokens_a = set(a.split())
    tokens_b = set(b.split())
    if not tokens_a and not tokens_b:
        return 1.0
    if not tokens_a or not tokens_b:
        return 0.0
    return len(tokens_a & tokens_b) / len(tokens_a | tokens_b)


def evaluate_extraction(
    extracted: Sequence[str],
    annotated: Sequence[str],
    *,
    similarity: Callable[[str, str], float],
    threshold: float,
) -> ExtractionEvalReport:
    """Compare `extracted` action items against `annotated` ones; return precision/recall.

    `extracted` and `annotated` are already-rendered strings (for a real pipeline
    task the caller joins `title` + `description` per `extraction_eval_design.md`
    §3.3; the annotation side is the reference sentence verbatim).

    `similarity(a, b) -> float in [0, 1]` decides how alike two strings are; a pair
    counts as the same item when its similarity is `>= threshold`. Both are
    required keyword arguments — see the module docstring for why they are never
    hardcoded.

    Matching is **global greedy one-to-one** (`extraction_eval_design.md` §3.4):
    every pair is scored, the pairs are sorted by similarity descending (ties
    broken by annotation index then extracted index, so runs are reproducible),
    and the list is walked taking any pair whose two endpoints are both still free
    and whose score clears `threshold`. A merge (two annotations → one extracted
    item) therefore surfaces as one true positive plus one missed annotation,
    rather than being hidden.

    Raises `ValueError` if `threshold`, or any score `similarity` returns, is not a
    real number in [0, 1] (NaN, infinity, out-of-range, non-numeric). A measuring
    tool must fail loudly here: a NaN score would otherwise be matched silently and
    reported as a perfect run.
    """
    threshold = _checked_unit_interval(threshold, "threshold")
    norm_extracted = [_normalize(item) for item in extracted]
    norm_annotated = [_normalize(item) for item in annotated]

    # Every (extracted, annotated) pair with its score. `ei` / `ai` are indices
    # back into the original (un-normalized) lists, kept for the report.
    scored_pairs = [
        (
            _checked_unit_interval(
                similarity(norm_e, norm_a), f"similarity({norm_e!r}, {norm_a!r})"
            ),
            ei,
            ai,
        )
        for ei, norm_e in enumerate(norm_extracted)
        for ai, norm_a in enumerate(norm_annotated)
    ]
    # Highest similarity first; deterministic tie-break by annotation then
    # extracted index (design §3.4).
    scored_pairs.sort(key=lambda pair: (-pair[0], pair[2], pair[1]))

    matched_extracted: set[int] = set()
    matched_annotated: set[int] = set()
    matched: list[MatchedPair] = []
    for score, ei, ai in scored_pairs:
        if score < threshold:
            break  # list is sorted descending — nothing after this clears the bar
        if ei in matched_extracted or ai in matched_annotated:
            continue
        matched_extracted.add(ei)
        matched_annotated.add(ai)
        matched.append(
            MatchedPair(extracted=extracted[ei], annotated=annotated[ai], similarity=score)
        )

    false_positives = [
        item for ei, item in enumerate(extracted) if ei not in matched_extracted
    ]
    missed = [item for ai, item in enumerate(annotated) if ai not in matched_annotated]

    true_positives = len(matched)
    if not extracted and not annotated:
        precision = recall = 1.0
    else:
        precision = true_positives / len(extracted) if extracted else 0.0
        recall = true_positives / len(annotated) if annotated else 1.0
    f1 = (
        2 * precision * recall / (precision + recall)
        if (precision + recall) > 0
        else 0.0
    )

    return ExtractionEvalReport(
        n_extracted=len(extracted),
        n_annotated=len(annotated),
        precision=precision,
        recall=recall,
        f1=f1,
        matched=matched,
        missed=missed,
        false_positives=false_positives,
    )


def evaluate_extracted_items(
    items: Sequence[ExtractedItem],
    annotated: Sequence[str],
    *,
    similarity: Callable[[str, str], float],
    threshold: float,
) -> ExtractionEvalReport:
    """`evaluate_extraction` for pipeline output: the extracted side as `ExtractedItem`s.

    The entry point for real extraction output. Taking `ExtractedItem` rather than
    `str` makes the contract a gate, not a description: an item reaches the eval
    only after it has validated against `extraction_contract.ExtractedItem`, and
    `to_comparison_text()` is the one place an item is rendered to the string the
    matcher compares (`extraction_eval_design.md` §3.3).

    A thin wrapper by design — no matching logic of its own; `similarity`,
    `threshold` and the report are exactly `evaluate_extraction`'s.
    """
    return evaluate_extraction(
        [item.to_comparison_text() for item in items],
        annotated,
        similarity=similarity,
        threshold=threshold,
    )
