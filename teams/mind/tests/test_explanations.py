"""The explanation in `ScoreResult.reasoning` (#358).

It is a fixed template over the score's own values: these tests pin that it
names only factors the score used, gives the real reason rather than the
category, explains low scores as well as high ones, and states missing data.
"""

from dataclasses import replace
from datetime import timedelta
from itertools import product

import pytest

from mind.evaluation.interfaces import Tier
from mind.scoring import features
from mind.scoring.factors import Factor
from mind.scoring.segmented_model import (
    ASSUMED_TIER,
    NEUTRAL,
    REASON_SHIFT,
    TIER_RULES,
    SegmentedRankingModel,
)
from tests.scoring_fakes import CONTEXT, NOW, LiveTask

MODEL = SegmentedRankingModel()

TIERS: tuple[Tier, ...] = ("operational", "tactical", "strategic")

#: Words only the phrase for that factor uses. A factor the explanation does
#: not list must not have any of its words in the text, and one it lists must.
MARKERS: dict[Factor, tuple[str, ...]] = {
    Factor.TIME_PRESSURE: ("deadline", "urgent now"),
    Factor.IMPACT: ("it mentions", "plays it down", "raises its impact"),
    Factor.QUICK_WIN: ("minutes", "effort estimate"),
}

#: The input grid the structural tests walk: every branch of the explanation,
#: including unknown tier, blocked, missing deadline and estimate, wording
#: that beats the deadline, and an overdue deadline.
GRID = list(
    product(
        (*TIERS, None),
        [None, NOW - timedelta(hours=2), NOW + timedelta(hours=3), NOW + timedelta(days=20)],
        [
            None,
            "Their production has been down for 30 minutes and customers are failing.",
            "Routine housekeeping; nobody is waiting on it.",
            "A customer asked.",
        ],
        (None, 10, 120),
        (True, False),
    )
)


def grid_tasks() -> list[LiveTask]:
    return [
        replace(
            LiveTask(),
            tier=tier,
            deadline=deadline,
            description=description,
            effort_estimate=effort,
            blocked=blocked,
        )
        for tier, deadline, description, effort, blocked in GRID
    ]


def reasoning_of(task: LiveTask) -> str:
    reasoning = MODEL.score(task, CONTEXT).reasoning
    assert reasoning is not None
    return reasoning


def without_subject(text: str) -> str:
    """The explanation with its tier name removed, so tiers compare on substance."""
    return text.split(" task, ", 1)[1]


# --- the text cannot drift from the score ------------------------------------


@pytest.mark.parametrize("task", grid_tasks())
def test_the_explanation_names_only_the_scores_factors(task: LiveTask) -> None:
    """The guard against an explanation that describes a score it did not come from."""
    result = MODEL.score(task, CONTEXT)
    explanation = MODEL.explain(task, CONTEXT)
    assert result.factors is not None

    assert explanation.text == result.reasoning
    assert {f.value for f in explanation.factors} <= set(result.factors.contributions)
    assert len(set(explanation.factors)) == len(explanation.factors)


@pytest.mark.parametrize("task", grid_tasks())
def test_the_text_talks_about_exactly_the_listed_factors(task: LiveTask) -> None:
    """Both directions: no listed factor is missing from the text, and no
    unlisted factor's words appear in it — so a phrase cannot be rendered
    under the wrong factor, or about a factor the score did not weigh."""
    explanation = MODEL.explain(task, CONTEXT)

    for factor, words in MARKERS.items():
        present = any(word in explanation.text for word in words)
        assert present == (factor in explanation.factors), (factor, explanation.text)


# --- a reason is a factor that moved the score -------------------------------


@pytest.mark.parametrize("task", grid_tasks())
def test_the_shifts_are_each_factors_distance_from_neutral(task: LiveTask) -> None:
    """Checked against the features directly, not against the model's own sums."""
    explanation = MODEL.explain(task, CONTEXT)
    result = MODEL.score(task, CONTEXT)
    assert result.factors is not None and result.factors.base_score is not None
    rule = TIER_RULES[task.tier or ASSUMED_TIER]
    values = {
        Factor.TIME_PRESSURE: features.time_pressure(
            task, NOW, rule.scale_hours, rule.missing_deadline
        ),
        Factor.IMPACT: features.impact(task),
        Factor.QUICK_WIN: features.quick_win(task),
    }

    assert set(explanation.shifts) == set(rule.weights)
    for factor, weight in rule.weights.items():
        assert explanation.shifts[factor] == pytest.approx(weight * (values[factor] - NEUTRAL))
    assert sum(explanation.shifts.values()) == pytest.approx(
        result.factors.base_score - NEUTRAL
    )


def test_effort_is_never_a_reason() -> None:
    """The threshold is the most quick_win can move a score, and it is strict.

    Pinned against the rules, not as a number: raising a quick_win weight
    above 0.10 would make it nameable, and this test would say so. Zero
    minutes is the one estimate that reaches the threshold exactly.
    """
    largest_weight = max(rule.weights.get(Factor.QUICK_WIN, 0.0) for rule in TIER_RULES.values())
    assert largest_weight * (1.0 - NEUTRAL) <= REASON_SHIFT

    for effort in (0, 1):
        task = replace(LiveTask(), tier="operational", effort_estimate=effort)
        assert Factor.QUICK_WIN not in MODEL.explain(task, CONTEXT).factors, effort


def test_a_neutral_task_names_no_reason() -> None:
    """A strategic task with no date and no impact words sits exactly on NEUTRAL."""
    explanation = MODEL.explain(replace(LiveTask(), tier="strategic", deadline=None), CONTEXT)

    assert explanation.text == (
        "strategic task, ranks in the middle: no factor stands out; no deadline given."
    )


def test_a_high_score_built_from_small_shifts_still_names_them() -> None:
    """No factor clears the threshold alone, but together they lift it to high.

    Saying "no single factor stands out" there would explain a high score
    with nothing; the factors that leaned that way are named instead.
    """
    task = replace(
        LiveTask(),
        tier="operational",
        deadline=NOW + timedelta(hours=6, minutes=30),
        description="A customer asked.",
        effort_estimate=10,
    )
    explanation = MODEL.explain(task, CONTEXT)

    assert all(abs(shift) <= REASON_SHIFT for shift in explanation.shifts.values())
    assert explanation.text == (
        "operational task, ranks high: it mentions customer; the deadline is 7 hours away."
    )


# --- the real reason, not the category ---------------------------------------


def test_a_near_deadline_is_named_in_hours() -> None:
    task = replace(LiveTask(), tier="operational", deadline=NOW + timedelta(hours=3))

    assert "the deadline is 3 hours away" in reasoning_of(task)


def test_one_hour_is_singular() -> None:
    task = replace(LiveTask(), tier="operational", deadline=NOW + timedelta(hours=1, minutes=10))

    assert "the deadline is 1 hour away" in reasoning_of(task)


@pytest.mark.parametrize(("hours", "said"), [(2.5, "3 hours"), (3.5, "4 hours")])
def test_half_hours_always_round_up(hours: float, said: str) -> None:
    """Python's round() sends 2.5 to 2 but 3.5 to 4; the text must not."""
    task = replace(LiveTask(), tier="operational", deadline=NOW + timedelta(hours=hours))

    assert f"the deadline is {said} away" in reasoning_of(task)


def test_a_far_deadline_is_named_in_days() -> None:
    task = replace(LiveTask(), tier="tactical", deadline=NOW + timedelta(days=6))

    assert "the deadline is 6 days away" in reasoning_of(task)


def test_a_passed_deadline_is_called_passed() -> None:
    task = replace(LiveTask(), deadline=NOW - timedelta(hours=2))

    assert "the deadline has passed" in reasoning_of(task)


def test_urgency_from_the_wording_is_not_credited_to_the_deadline() -> None:
    """`time_pressure` is a max: when the wording wins, the deadline did not set it."""
    task = replace(
        LiveTask(),
        tier="operational",
        deadline=NOW + timedelta(hours=30),
        description="Their production has been down for 30 minutes.",
    )
    reasoning = reasoning_of(task)

    assert 'its wording says it is urgent now ("has been down")' in reasoning
    assert "30 hours" not in reasoning


def test_impact_names_the_words_that_raised_it() -> None:
    task = replace(LiveTask(), tier="strategic", description="The board and investors are asking.")

    assert "it mentions board, investors" in reasoning_of(task)


def test_a_stem_match_is_printed_as_the_whole_word() -> None:
    task = replace(
        LiveTask(), tier="strategic", description="Support escalated it; customers are upset."
    )

    assert "it mentions customers, escalated" in reasoning_of(task)


def test_a_phrase_quoted_for_urgency_is_not_repeated_for_impact() -> None:
    """"data is at risk" is in both lexicons; the sentence says it once."""
    task = replace(
        LiveTask(),
        tier="tactical",
        deadline=None,
        description="Backup data is at risk until the job is fixed for the customer.",
    )
    reasoning = reasoning_of(task)

    assert reasoning.count("data is at risk") == 1
    assert "it mentions customer" in reasoning


# --- low scores are explained too --------------------------------------------


def test_a_low_score_says_what_holds_it_down() -> None:
    task = replace(
        LiveTask(),
        tier="tactical",
        deadline=NOW + timedelta(days=6),
        description="Routine housekeeping; nobody is waiting on it.",
    )

    assert reasoning_of(task) == (
        "tactical task, ranks low: its wording plays it down "
        "(nobody is waiting, housekeeping, routine); the deadline is 6 days away."
    )


def test_a_blocked_task_is_not_said_to_rank_high() -> None:
    """Its final score is below every startable task, whatever its base."""
    task = replace(LiveTask(), deadline=NOW + timedelta(hours=2), blocked=True)
    reasoning = reasoning_of(task)

    assert reasoning.startswith("tactical task, would rank high: ")
    assert reasoning.endswith("; blocked, so it cannot outrank anything startable.")


# --- missing data is stated, not dropped -------------------------------------


@pytest.mark.parametrize("tier", TIERS)
def test_a_missing_deadline_is_stated(tier: Tier) -> None:
    """Whether it pulls the score down (operational, tactical) or is neutral (strategic)."""
    reasoning = reasoning_of(replace(LiveTask(), tier=tier, deadline=None))

    assert reasoning.count("no deadline given") == 1


def test_a_missing_deadline_is_stated_even_when_the_wording_set_the_pressure() -> None:
    task = replace(
        LiveTask(), tier="operational", deadline=None, description="Their production has been down."
    )
    reasoning = reasoning_of(task)

    assert 'urgent now ("has been down")' in reasoning
    assert reasoning.count("no deadline given") == 1


@pytest.mark.parametrize("tier", ["operational", "tactical"])
def test_a_missing_estimate_is_stated_where_effort_is_weighed(tier: Tier) -> None:
    reasoning = reasoning_of(replace(LiveTask(), tier=tier, effort_estimate=None))

    assert "no effort estimate" in reasoning


def test_effort_is_not_mentioned_where_the_rule_does_not_weigh_it() -> None:
    """Strategic has no quick_win, so neither the estimate nor its absence is a reason."""
    for effort in (None, 5):
        task = replace(LiveTask(), tier="strategic", effort_estimate=effort)
        explanation = MODEL.explain(task, CONTEXT)

        assert Factor.QUICK_WIN not in explanation.factors
        assert "effort" not in explanation.text
        assert "minutes" not in explanation.text


# --- the explanation differs by tier -----------------------------------------


def test_the_same_task_is_explained_differently_by_each_tier() -> None:
    """Compared without the tier's name, which alone would make any three differ.

    An undated task that mentions a customer: the missing date is a strong
    reason against at operational, a weaker one at tactical where the customer
    weighs more, and neutral at strategic where the customer decides it.
    """
    task = replace(LiveTask(), deadline=None, description="A customer is waiting on it.")
    bodies = {tier: without_subject(reasoning_of(replace(task, tier=tier))) for tier in TIERS}

    assert bodies == {
        "operational": "ranks low: no deadline given.",
        "tactical": "ranks in the middle; in its favour: it mentions customer; "
        "against it: no deadline given.",
        "strategic": "ranks high: it mentions customer; no deadline given.",
    }


def test_no_explanation_mentions_initiative() -> None:
    """The factor was removed in #797; its phrasing must not survive it."""
    for task in grid_tasks():
        assert "initiative" not in reasoning_of(task)
