"""Scoring a task whose tier, effort estimate or description is missing.

A real task can arrive with any of the three empty: extraction leaves them
null on purpose when the content gives no basis (#351, #352), and Inbox's Task
allows it. The scenario Task in `test_scoring.py` cannot express that, so these
tests build the shape the live path will hand the model.
"""

import logging
from dataclasses import dataclass, replace
from datetime import datetime, timedelta

import pytest

from mind.evaluation.interfaces import ScoreContext, Tier
from mind.scoring.confidence import ASSUMED_TIER_SHIFT, score_confidence
from mind.scoring.factors import Factor
from mind.scoring.features import task_text
from mind.scoring.segmented_model import ASSUMED_TIER, TIER_RULES, SegmentedRankingModel

NOW = datetime(2026, 9, 7, 9, 0)  # noqa: DTZ001
CONTEXT = ScoreContext(current_datetime=NOW)
MODEL = SegmentedRankingModel()

TIERS: tuple[Tier, ...] = ("operational", "tactical", "strategic")


@dataclass(frozen=True)
class LiveTask:
    """What the live path builds from an Inbox task: some fields may be empty."""

    title: str = "Do the thing"
    description: str | None = "A task with no particular signals in its wording."
    # A day and a bit out: near enough that each tier's sense of time reads it
    # differently, which is what makes the tiers score it apart.
    deadline: datetime | None = NOW + timedelta(hours=30)
    effort_estimate: int | None = 30
    tier: Tier | None = "tactical"
    blocked: bool = False
    parent_task_id: str | None = None


# --- a missing tier ----------------------------------------------------------


def test_a_task_with_no_tier_is_scored_by_the_tactical_rule() -> None:
    """Tactical sits in the middle of both axes, so it assumes the least.

    Its sense of time (72 hours) is between 8 and 336, and its weights are
    between the other two tiers'. The factors are compared, not just the
    score: the same number reached by a different rule would be a coincidence.
    """

    unknown = MODEL.score(replace(LiveTask(), tier=None), CONTEXT)
    tactical = MODEL.score(replace(LiveTask(), tier="tactical"), CONTEXT)

    assert unknown.score == tactical.score
    assert unknown.factors == tactical.factors


def test_the_task_above_tells_the_three_tiers_apart() -> None:
    """Guards the test above. Were all three tiers to score this task alike,
    matching tactical would prove nothing about which rule was used."""

    scores = {MODEL.score(replace(LiveTask(), tier=tier), CONTEXT).score for tier in TIERS}

    assert len(scores) == 3


def test_an_assumed_tier_is_logged(caplog: pytest.LogCaptureFixture) -> None:
    """Countable, so the null rate can be measured once real tasks flow.

    Below about 10% the assumption is enough; above it, a separate call just
    for tier may be worth its cost.
    """

    with caplog.at_level(logging.INFO, logger="mind.scoring"):
        MODEL.score(replace(LiveTask(), tier=None), CONTEXT)

    assert "tier_assumed" in caplog.text


# --- a missing effort estimate -----------------------------------------------


def test_no_estimate_scores_like_a_task_too_long_for_the_bonus() -> None:
    """No bonus, and no penalty either.

    quick_win rewards a shortness we know about. Without an estimate there is
    no evidence the task is short, so it gets what a task past the cap gets —
    nothing — and is never ranked below one.
    """

    for tier in ("operational", "tactical"):
        unknown = MODEL.score(replace(LiveTask(), tier=tier, effort_estimate=None), CONTEXT)
        too_long = MODEL.score(replace(LiveTask(), tier=tier, effort_estimate=120), CONTEXT)

        assert unknown.score == too_long.score, tier


def test_no_estimate_changes_nothing_where_effort_is_not_weighed() -> None:
    """Strategic has no quick_win at all, so the estimate cannot matter there."""

    unknown = MODEL.score(replace(LiveTask(), tier="strategic", effort_estimate=None), CONTEXT)
    known = MODEL.score(replace(LiveTask(), tier="strategic", effort_estimate=15), CONTEXT)

    assert unknown.score == known.score


# --- how far to trust a score built on a missing field -----------------------


def test_a_task_with_nothing_missing_is_fully_trusted() -> None:
    assert score_confidence(LiveTask()) == 1.0


def test_a_missing_tier_lowers_confidence_by_the_measured_shift() -> None:
    assert score_confidence(replace(LiveTask(), tier=None)) == 1.0 - ASSUMED_TIER_SHIFT


def test_a_missing_estimate_costs_exactly_what_the_bonus_weighs_in_that_rule() -> None:
    """Derived from the rule, not measured: the bonus cannot move a score by
    more than its own weight, so that weight is the worst case. Strategic has
    no bonus, so there a missing estimate costs nothing."""

    for tier in TIERS:
        weight = TIER_RULES[tier].weights.get(Factor.QUICK_WIN, 0.0)
        task = replace(LiveTask(), tier=tier, effort_estimate=None)

        assert score_confidence(task) == pytest.approx(1.0 - weight), tier


def test_the_effort_penalty_really_is_the_worst_case() -> None:
    """Checked against the model, so the claim above cannot drift from it."""

    for tier in TIERS:
        shortest = MODEL.score(replace(LiveTask(), tier=tier, effort_estimate=1), CONTEXT)
        unknown = MODEL.score(replace(LiveTask(), tier=tier, effort_estimate=None), CONTEXT)
        assert shortest.factors is not None and unknown.factors is not None
        assert shortest.factors.base_score is not None and unknown.factors.base_score is not None
        shift = abs(shortest.factors.base_score - unknown.factors.base_score)

        assert shift <= 1.0 - score_confidence(replace(LiveTask(), tier=tier, effort_estimate=None)) + 1e-9


def test_a_missing_tier_costs_more_than_a_missing_estimate() -> None:
    """The tier picks the whole rule; the estimate feeds one small bonus."""

    no_tier = score_confidence(replace(LiveTask(), tier=None))
    for tier in TIERS:
        no_estimate = score_confidence(replace(LiveTask(), tier=tier, effort_estimate=None))
        assert no_tier < no_estimate, tier


def test_both_missing_compound() -> None:
    no_tier = score_confidence(replace(LiveTask(), tier=None))
    no_estimate = score_confidence(replace(LiveTask(), tier=ASSUMED_TIER, effort_estimate=None))
    both = score_confidence(replace(LiveTask(), tier=None, effort_estimate=None))

    assert both == pytest.approx(no_tier * no_estimate)


# --- what mypy cannot see: None turned into text -----------------------------


def test_the_explanation_says_the_tier_is_unknown_rather_than_none() -> None:
    """An f-string accepts None and prints it, so no type checker objects."""

    reasoning = MODEL.score(replace(LiveTask(), tier=None), CONTEXT).reasoning
    assert reasoning is not None

    assert "None" not in reasoning
    assert reasoning.startswith(f"task of unknown tier, scored as {ASSUMED_TIER}, led by ")


def test_the_explanation_of_a_known_tier_is_unchanged() -> None:
    reasoning = MODEL.score(replace(LiveTask(), tier="operational"), CONTEXT).reasoning
    assert reasoning is not None

    assert reasoning.startswith("operational task, led by ")


def test_a_missing_description_adds_no_words_to_the_keyword_text() -> None:
    """f"{None}" is the word "none", which the impact lexicon would then read."""

    assert "none" not in task_text(replace(LiveTask(), title="Fix login", description=None))


def test_a_missing_description_scores_like_an_empty_one() -> None:
    missing = MODEL.score(replace(LiveTask(), description=None), CONTEXT)
    empty = MODEL.score(replace(LiveTask(), description=""), CONTEXT)

    assert missing.score == empty.score
