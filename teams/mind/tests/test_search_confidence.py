"""#476: confidence_from_similarity() and score_above_threshold()."""

from unittest.mock import AsyncMock

import pytest

from mind.embeddings.base import SearchResult
from mind.embeddings.confidence import confidence_from_similarity
from mind.embeddings.duplicate_detection import find_duplicate
from mind.embeddings.related_tasks import find_related


def test_a_near_identical_pair_scores_high():
    assert confidence_from_similarity(1.0, threshold=0.711) == pytest.approx(1.0)


def test_a_hit_right_at_threshold_scores_zero():
    assert confidence_from_similarity(0.80, threshold=0.80) == pytest.approx(0.0)


def test_a_pair_just_over_threshold_scores_low():
    assert confidence_from_similarity(0.805, threshold=0.80) < 0.1


def test_scale_is_bounded_zero_to_one():
    for similarity in (0.0, 0.3, 0.666, 0.9, 1.0):
        confidence = confidence_from_similarity(similarity, threshold=0.666)
        assert 0.0 <= confidence <= 1.0


def test_confidence_is_monotonic_in_similarity_above_threshold():
    low = confidence_from_similarity(0.75, threshold=0.70)
    high = confidence_from_similarity(0.95, threshold=0.70)
    assert high > low


def test_double_the_distance_above_threshold_is_double_the_confidence():
    threshold = 0.70
    near = confidence_from_similarity(0.75, threshold)  # 0.05 above
    far = confidence_from_similarity(0.80, threshold)  # 0.10 above
    assert far == pytest.approx(2 * near)


def test_a_threshold_of_one_never_divides_by_zero():
    assert confidence_from_similarity(1.0, threshold=1.0) == pytest.approx(1.0)
    assert confidence_from_similarity(0.99, threshold=1.0) == pytest.approx(0.0)


async def test_a_candidate_below_threshold_is_dropped_not_kept_at_zero_confidence():
    store = AsyncMock()
    store.get_vector.return_value = [0.0] * 4
    store.search.return_value = [SearchResult(task_id="task-2", similarity=0.5)]

    results = await find_related(store, "task-1", threshold=0.711)

    assert results == []


async def test_a_returned_related_task_always_carries_a_confidence():
    store = AsyncMock()
    store.get_vector.return_value = [0.0] * 4
    store.search.return_value = [SearchResult(task_id="task-2", similarity=0.9)]

    [result] = await find_related(store, "task-1", threshold=0.711)

    assert result.confidence == pytest.approx(confidence_from_similarity(0.9, 0.711))


async def test_a_duplicate_just_over_threshold_scores_low_confidence():
    store = AsyncMock()
    store.search.return_value = [SearchResult(task_id="task-existing", similarity=0.905)]

    result = await find_duplicate(store, "Fix the login bug", "On the payments page", threshold=0.90)

    assert result is not None
    assert result.confidence == pytest.approx(confidence_from_similarity(0.905, 0.90))
    assert result.confidence < 0.1
