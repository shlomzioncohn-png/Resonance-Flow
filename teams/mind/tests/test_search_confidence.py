"""#476: confidence_from_similarity() -- the pure similarity-to-confidence
mapping behind find_duplicate(), find_related() and dependency-edge
resolution. See embeddings/README.md, "Confidence"."""

from unittest.mock import AsyncMock

import pytest

from mind.embeddings.base import SearchResult
from mind.embeddings.confidence import confidence_from_similarity
from mind.embeddings.related_tasks import find_related


def test_a_near_identical_pair_scores_high():
    """similarity=1.0 is as confident as it gets, regardless of threshold."""
    assert confidence_from_similarity(1.0, threshold=0.711) == pytest.approx(1.0)


def test_a_hit_right_at_threshold_is_the_most_marginal_call_there_is():
    """The exact case #476 calls out: a marginal call must score low, not
    whatever the raw similarity happens to look like."""
    assert confidence_from_similarity(0.80, threshold=0.80) == pytest.approx(0.0)


def test_a_pair_just_over_threshold_scores_low():
    assert confidence_from_similarity(0.805, threshold=0.80) < 0.1


def test_scale_is_bounded_zero_to_one_like_the_team_convention():
    """Same scale as R2's AssigneeCandidate.confidence / *_confidence
    fields (#132/#360): a plain float in [0.0, 1.0]."""
    for similarity in (0.0, 0.3, 0.666, 0.9, 1.0):
        confidence = confidence_from_similarity(similarity, threshold=0.666)
        assert 0.0 <= confidence <= 1.0


def test_confidence_is_monotonic_in_similarity_above_threshold():
    """Strictly more similar must never score as, or less, confident."""
    low = confidence_from_similarity(0.75, threshold=0.70)
    high = confidence_from_similarity(0.95, threshold=0.70)
    assert high > low


def test_double_the_distance_above_threshold_is_double_the_confidence():
    """Linear, not just monotonic -- the mapping #476 asks for, not some
    other curve that happens to also go up."""
    threshold = 0.70
    near = confidence_from_similarity(0.75, threshold)  # 0.05 above
    far = confidence_from_similarity(0.80, threshold)  # 0.10 above
    assert far == pytest.approx(2 * near)


def test_a_threshold_of_one_never_divides_by_zero():
    """Edge case: span (1.0 - threshold) is 0. Only an exact match can
    even reach this threshold, so it alone gets full confidence."""
    assert confidence_from_similarity(1.0, threshold=1.0) == pytest.approx(1.0)
    assert confidence_from_similarity(0.99, threshold=1.0) == pytest.approx(0.0)


async def test_the_no_results_case_carries_no_confidence():
    """#476 point 5: 'nothing in the index is related' is itself an
    inference, and the documented decision (embeddings/README.md,
    "Confidence") is that it carries no confidence value -- there's no
    specific claim being graded, just an empty list."""
    store = AsyncMock()
    store.get_vector.return_value = [0.0] * 4
    store.search.return_value = []

    results = await find_related(store, "task-1", threshold=0.5)

    assert results == []


async def test_a_returned_related_task_always_carries_a_confidence():
    store = AsyncMock()
    store.get_vector.return_value = [0.0] * 4
    store.search.return_value = [SearchResult(task_id="task-2", similarity=0.9)]

    [result] = await find_related(store, "task-1", threshold=0.711)

    assert result.confidence == pytest.approx(confidence_from_similarity(0.9, 0.711))
