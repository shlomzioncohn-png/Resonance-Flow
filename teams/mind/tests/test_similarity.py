"""Tests for mind.evaluation.similarity.

Pins what the lexical backend does, including its two known limits (synonyms
score low; a contained short text scores 1.0), so a change in either shows up
here before it shows up in an eval number.
"""

import pytest

from mind.evaluation.extraction_eval import evaluate_extraction
from mind.evaluation.similarity import LEXICAL_THRESHOLD, lexical_similarity

GOLD_UI = "The User Interface Designer will make a presentation on the user interface concept."


def test_identical_texts_score_one() -> None:
    assert lexical_similarity(GOLD_UI, GOLD_UI) == 1.0


@pytest.mark.parametrize(
    ("a", "b"),
    [
        (GOLD_UI, "UI designer: present interface concept"),
        ("send the minutes", "book the board room for monday"),
        ("", "send the minutes"),
    ],
)
def test_score_is_between_zero_and_one(a: str, b: str) -> None:
    assert 0.0 <= lexical_similarity(a, b) <= 1.0


def test_word_order_does_not_matter() -> None:
    assert lexical_similarity("send the minutes today", "today send the minutes") == 1.0


def test_case_and_punctuation_do_not_matter() -> None:
    assert lexical_similarity("Send the minutes, today.", "send the minutes today") == 1.0


def test_same_task_in_other_words_clears_the_threshold() -> None:
    assert (
        lexical_similarity(GOLD_UI, "UI designer: present interface concept") >= LEXICAL_THRESHOLD
    )


def test_unrelated_tasks_stay_below_the_threshold() -> None:
    gold = "The Project Manager will send the minutes to everyone."
    assert lexical_similarity(gold, "Book the board room for Monday") < LEXICAL_THRESHOLD


def test_empty_text_scores_zero() -> None:
    assert lexical_similarity("", "send the minutes") == 0.0
    assert lexical_similarity("...", "send the minutes") == 0.0


# --- the two known limits ---------------------------------------------------------


def test_known_limit_synonyms_score_below_the_threshold() -> None:
    """Same commitment, different verbs: lexical matching misses it (a false FN)."""

    gold = "The Project Manager will research companies that can help with voice recognition."
    assert lexical_similarity(gold, "PM to find vendors for speech input") < LEXICAL_THRESHOLD


def test_known_limit_contained_text_scores_one() -> None:
    """Every word of the short text is in the long one, so the score is 1.0."""

    gold = "The Project Manager will send the minutes to everyone."
    assert lexical_similarity(gold, "send the minutes") == 1.0


# --- wired into the harness ---------------------------------------------------------


def test_works_as_the_harness_similarity() -> None:
    report = evaluate_extraction(
        ["UI designer: present interface concept", "Book the board room for Monday"],
        [GOLD_UI],
        similarity=lexical_similarity,
        threshold=LEXICAL_THRESHOLD,
    )

    assert report.true_positives == 1
    assert report.false_positives == ["Book the board room for Monday"]
    assert report.missed == []
