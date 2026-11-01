"""#476: confidence is distance above a decision threshold, not raw
similarity. See embeddings/README.md, "Confidence"."""

from collections.abc import Iterable

from pydantic import Field

from mind.embeddings.base import SearchResult


class ScoredResult(SearchResult):
    """A SearchResult that cleared a threshold. confidence is required --
    unlike a raw search() hit, there's always a threshold to measure it
    against by this point."""

    confidence: float = Field(ge=0.0, le=1.0)


def confidence_from_similarity(similarity: float, threshold: float) -> float:
    """0.0 at threshold itself, 1.0 at similarity=1.0, linear between."""
    span = 1.0 - threshold
    if span <= 0:
        return 1.0 if similarity >= threshold else 0.0
    return max(0.0, min(1.0, (similarity - threshold) / span))


def score_above_threshold(candidates: Iterable[SearchResult], threshold: float) -> list[ScoredResult]:
    """Every candidate clearing `threshold`, as a ScoredResult."""
    return [
        ScoredResult(**c.model_dump(), confidence=confidence_from_similarity(c.similarity, threshold))
        for c in candidates
        if c.similarity >= threshold
    ]
