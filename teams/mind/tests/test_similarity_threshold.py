"""The lexical threshold checked on the hand-labelled pairs of `extraction_eval_design.md` §5.

Gold sentences are real AMI items; the extracted side is the invented one from §5,
and which pairs are "the same task" was decided by hand in September, before any
pipeline output existed. Checking τ against them is a design check, not tuning
(§6, "Lock discipline").

What the check found (2026-10-25, §6): pair by pair, τ = 0.60 does not separate
the two groups. Same-task pairs score 0.69 and up, but different-task pairs from
the same meeting reach 0.72, because the meetings repeat the same words
("designer", "present", "concept"). One-to-one matching still picks exactly the
hand-labelled pairs, because each true pair outscores the wrong ones. These
tests pin that, including when the extractor misses one item.
"""

import pytest

from mind.evaluation.similarity import (
    EMBEDDING_THRESHOLD,
    LEXICAL_THRESHOLD,
    embedding_similarity,
    lexical_similarity,
)
from mind.evaluation.threshold_pairs import (
    MEETINGS,
    matched_ids,
    matching_failures,
    missing_one_cases,
)

LEXICAL = {"similarity": lexical_similarity, "threshold": LEXICAL_THRESHOLD}


@pytest.mark.parametrize("meeting", list(MEETINGS))
def test_every_same_task_pair_clears_the_threshold(meeting: str) -> None:
    gold, extracted, same = MEETINGS[meeting]
    for g, e in same:
        assert lexical_similarity(gold[g], extracted[e]) >= LEXICAL_THRESHOLD, (g, e)


@pytest.mark.parametrize("meeting", list(MEETINGS))
def test_matching_finds_exactly_the_hand_labelled_pairs(meeting: str) -> None:
    _, extracted, same = MEETINGS[meeting]
    assert matched_ids(meeting, list(extracted), **LEXICAL) == same


@pytest.mark.parametrize(("meeting", "missing"), missing_one_cases())
def test_a_missed_item_does_not_pull_in_a_wrong_match(meeting: str, missing: str) -> None:
    """The extractor misses one item: its gold sentence stays unmatched, nothing else moves."""

    _, extracted, same = MEETINGS[meeting]
    keep = [e for e in extracted if e != missing]
    assert matched_ids(meeting, keep, **LEXICAL) == {(g, e) for g, e in same if e != missing}


# -- the embedding backend's τ (§6, frozen 2026-10-28) -------------------------


@pytest.fixture(scope="module")
def embedding():
    # The model is downloaded in CI and under models/ locally; where it cannot
    # load (no network, no local copy), there is nothing to check.
    try:
        embedding_similarity("a", "a")
    except Exception as e:  # noqa: BLE001 - any load failure means "no model here"
        pytest.skip(f"embedding model not available: {e}")
    return embedding_similarity


def test_the_embedding_threshold_is_frozen() -> None:
    assert EMBEDDING_THRESHOLD == 0.70


def test_the_embedding_threshold_passes_every_matching_check(embedding) -> None:
    assert matching_failures(embedding, EMBEDDING_THRESHOLD) == []
