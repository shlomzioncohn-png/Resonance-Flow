"""Acceptance tests for the segmented-ranking baseline.

These run on hand-built tasks, not on persona scenarios: they pin down the
*properties* the issue asks for (three genuinely separate rules, determinism,
defined behaviour for both nullable fields, a valid 0-1 output, and a blocked
task that can never win), independently of how any particular scenario scores.
"""

from datetime import datetime, timedelta
from itertools import product

import pytest

from mind.evaluation.interfaces import ScoreContext, Task, Tier
from mind.scoring import features
from mind.scoring.factors import FACTOR_NAMES, Factor
from mind.scoring.features import impact
from mind.scoring.segmented_model import (
    BLOCKED_CEILING,
    TIER_RULES,
    UNBLOCKED_FLOOR,
    SegmentedRankingModel,
)

# The persona set's timestamps are timezone-naive by schema (PersonaEvaluationScenario),
# so the model is only ever handed naive datetimes; tests must match.
NOW = datetime(2026, 9, 7, 9, 0)  # noqa: DTZ001
CONTEXT = ScoreContext(current_datetime=NOW)
MODEL = SegmentedRankingModel()

TIERS: tuple[Tier, ...] = ("operational", "tactical", "strategic")


def make_task(
    *,
    tier: Tier = "operational",
    title: str = "Do the thing",
    description: str = "A task with no particular signals in its wording.",
    deadline: datetime | None = None,
    effort_estimate: int = 30,
    blocked: bool = False,
    parent_task_id: str | None = None,
    task_id: str = "task_a",
) -> Task:
    return Task(
        task_id=task_id,
        title=title,
        description=description,
        deadline=deadline,
        effort_estimate=effort_estimate,
        tier=tier,
        blocked=blocked,
        parent_task_id=parent_task_id,
    )


def contributions_of(task: Task) -> dict[str, float]:
    """The weighted contributions this model reported for `task`."""
    factors = MODEL.score(task, CONTEXT).factors
    assert factors is not None
    return factors.contributions


# --- the rules are genuinely one per tier ------------------------------------


def test_each_tier_weights_sum_to_one() -> None:
    """The normalisation claim: a rule's weighted sum is already inside 0-1."""
    for tier, rule in TIER_RULES.items():
        assert sum(rule.weights.values()) == pytest.approx(1.0), tier


def test_each_feature_can_reach_its_maximum() -> None:
    """The other half of the reachability claim, and the half that owns the lexicon.

    `test_every_rule_can_reach_a_full_base_score` forces the features to 1.0,
    so it proves the weights add up but says nothing about whether a real task
    can get them there. This one does only that, which is why the wording and
    the lexicon belong here rather than in a test about tier weights.
    """
    loud = make_task(
        title="Customer outage",
        description="A customer outage is corrupting records and the SLA is breached.",
        deadline=NOW - timedelta(hours=1),  # already passed: maximum time pressure
        effort_estimate=1,  # the smallest a task may claim
    )

    assert features.time_pressure(loud, NOW, 8.0, 0.15) == pytest.approx(1.0)
    assert features.impact(loud) == pytest.approx(1.0)
    # Derived from the cap, not written as a number: `quick_win` cannot reach a
    # flat 1.0 while a task must claim at least one minute, and a tolerance
    # tight enough to mean anything would break the next time the cap moves.
    assert features.quick_win(loud) == pytest.approx(1.0 - 1.0 / features.QUICK_WIN_CAP_MINUTES)


def test_every_rule_can_reach_a_full_base_score(monkeypatch: pytest.MonkeyPatch) -> None:
    """A maxed task must reach a base of 1.0, not merely weights that sum to 1.0.

    The sum check alone is satisfied by a weight parked on a feature that can
    never leave 0, which silently shortens that rule's scale — the removed
    `initiative` weight capped the strategic rule at 0.95 while the other two
    reached 1.0, penalising strategic tasks wherever tiers compete directly.

    Every feature is forced to 1.0 rather than coaxed there with wording and a
    1-minute estimate: this is a statement about `TIER_RULES`, and it should
    fail only when a rule's weights are wrong — not when the impact lexicon is
    retuned or the phrase matcher changes.
    """
    monkeypatch.setattr(features, "time_pressure", lambda *_args: 1.0)
    monkeypatch.setattr(features, "impact", lambda _task: 1.0)
    monkeypatch.setattr(features, "quick_win", lambda _task: 1.0)

    for tier in TIERS:
        factors = MODEL.score(make_task(tier=tier), CONTEXT).factors

        assert factors is not None
        assert factors.base_score == pytest.approx(1.0), (tier, factors)


def test_tiers_use_different_feature_sets() -> None:
    """`contributions` names exactly the features that tier's rule actually used."""
    used = {tier: set(contributions_of(make_task(tier=tier))) for tier in TIERS}

    assert used["operational"] == {"time_pressure", "impact", "quick_win"}
    assert used["tactical"] == {"time_pressure", "impact", "quick_win"}
    assert used["strategic"] == {"time_pressure", "impact"}


def test_factor_names_come_from_the_fixed_vocabulary() -> None:
    """No free-form keys: #358 matches explanation factors against these names."""
    for tier, blocked in product(TIERS, (True, False)):
        names = set(contributions_of(make_task(tier=tier, blocked=blocked)))

        assert names <= FACTOR_NAMES, (tier, blocked, names - FACTOR_NAMES)


def test_deadline_weighs_less_as_the_tier_gets_more_strategic() -> None:
    """The same near deadline buys progressively less across the three rules.

    This is the segmentation itself: swap the tier and nothing else, and the
    answer has to move. If it did not, the three rules would be one rule.
    """
    soon = NOW + timedelta(hours=2)
    weighted = [
        contributions_of(make_task(tier=tier, deadline=soon))["time_pressure"] for tier in TIERS
    ]

    assert weighted[0] > weighted[1] > weighted[2]


def test_impact_weighs_more_as_the_tier_gets_more_strategic() -> None:
    """The mirror of the deadline ordering, and the reason the two cross."""
    wording = {
        "title": "Customer production outage",
        "description": "A customer production system is down and the SLA is at risk.",
    }
    weighted = [contributions_of(make_task(tier=tier, **wording))["impact"] for tier in TIERS]

    assert weighted[0] < weighted[1] < weighted[2]


def test_swapping_operational_and_strategic_inputs_changes_the_answer() -> None:
    """A dated chore vs. an undated high-impact item flips with the tier.

    Under one shared formula these two would keep their order whatever the tier
    said; that they swap is what proves the rules are separate.
    """
    dated_chore = {"deadline": NOW + timedelta(hours=4), "description": "Routine housekeeping."}
    undated_heavy = {
        "deadline": None,
        "description": "The board and investors are affected; a customer outage is ongoing.",
    }

    op_chore = MODEL.score(make_task(tier="operational", **dated_chore), CONTEXT).score
    op_heavy = MODEL.score(make_task(tier="operational", **undated_heavy), CONTEXT).score
    st_chore = MODEL.score(make_task(tier="strategic", **dated_chore), CONTEXT).score
    st_heavy = MODEL.score(make_task(tier="strategic", **undated_heavy), CONTEXT).score

    assert op_chore > st_chore  # the deadline is worth more to the operational rule
    assert st_heavy > op_heavy  # the wording is worth more to the strategic rule


# --- what the factors report -------------------------------------------------


def test_factors_report_the_blocked_adjustment_and_the_pre_band_score() -> None:
    """`blocked` records that the model adjusted, and `base_score` what it adjusted."""
    startable = MODEL.score(make_task(blocked=False), CONTEXT)
    blocked = MODEL.score(make_task(blocked=True), CONTEXT)

    assert startable.factors is not None
    assert blocked.factors is not None
    assert startable.factors.blocked is False
    assert blocked.factors.blocked is True

    for result in (startable, blocked):
        factors = result.factors
        assert factors is not None
        assert factors.base_score == pytest.approx(sum(factors.contributions.values()))


# --- determinism -------------------------------------------------------------


def test_same_input_scores_identically_on_repeated_runs() -> None:
    """No clock reads, no randomness, no model calls — or the baseline is worthless."""
    task = make_task(
        tier="tactical",
        title="Return the redlined contract",
        description="A customer is waiting and the SLA is at risk.",
        deadline=NOW + timedelta(hours=30),
    )
    first = MODEL.score(task, CONTEXT)
    second = SegmentedRankingModel().score(task, CONTEXT)

    assert first.score == second.score
    assert first.factors == second.factors
    assert first.reasoning == second.reasoning


# --- the two nullable fields -------------------------------------------------


@pytest.mark.parametrize("tier", TIERS)
def test_missing_deadline_is_defined_and_not_treated_as_zero(tier: Tier) -> None:
    """`deadline is None` reads as that tier's stated fallback, never as 0.0."""
    contribution = contributions_of(make_task(tier=tier, deadline=None))["time_pressure"]
    rule = TIER_RULES[tier]

    assert contribution == pytest.approx(rule.weights[Factor.TIME_PRESSURE] * rule.missing_deadline)
    assert contribution > 0.0


def test_missing_deadline_is_read_most_generously_at_strategic() -> None:
    """Absence of a date is not evidence of unimportance where most work has none."""
    fallbacks = [TIER_RULES[tier].missing_deadline for tier in TIERS]

    assert fallbacks[0] < fallbacks[1] < fallbacks[2]


@pytest.mark.parametrize("tier", TIERS)
def test_parent_task_id_does_not_affect_the_score(tier: Tier) -> None:
    """The baseline does not read `parent_task_id` at all, at any tier.

    Not an optimisation — a boundary. Hierarchy belongs to the week-7 stretch,
    and the comparison it is measured against is only honest if the baseline
    knows nothing about it. A weak signal here would also be close to
    information-free in production, where almost every task has a parent.
    """
    without = MODEL.score(make_task(tier=tier, parent_task_id=None), CONTEXT)
    with_parent = MODEL.score(make_task(tier=tier, parent_task_id="task_parent"), CONTEXT)

    assert without.score == with_parent.score
    assert without.factors == with_parent.factors


def test_initiative_is_not_part_of_the_factor_vocabulary() -> None:
    """Removed with the feature: a name nothing can produce must not be offerable."""
    assert "initiative" not in FACTOR_NAMES
    assert not hasattr(Factor, "INITIATIVE")


def test_the_strategic_rule_keeps_its_time_to_impact_ratio() -> None:
    """The freed 0.05 was redistributed in proportion, not reassigned.

    `initiative` held 0.05 of the strategic rule. Renormalising the two
    remaining weights over 0.95 keeps their ratio, so no weight was re-chosen
    when the feature went away — the only change is that 0.05 is no longer
    parked on a feature that always returned 0.
    """
    weights = TIER_RULES["strategic"].weights

    assert weights[Factor.TIME_PRESSURE] == pytest.approx(0.21)
    assert weights[Factor.IMPACT] == pytest.approx(0.79)
    assert weights[Factor.TIME_PRESSURE] / weights[Factor.IMPACT] == pytest.approx(
        0.20 / 0.75, rel=0.01
    )


# --- blocked can never win ---------------------------------------------------


def test_a_blocked_task_never_outranks_an_unblocked_one() -> None:
    """The guarantee, checked over every corner of the input space at once.

    Not "blocked usually loses" — the bands make it arithmetic: every blocked
    score lands in [0, BLOCKED_CEILING] and every unblocked one in
    [UNBLOCKED_FLOOR, 1], and the ceiling is strictly below the floor.
    """
    deadlines = [
        None,
        NOW - timedelta(days=5),
        NOW,
        NOW + timedelta(hours=1),
        NOW + timedelta(days=90),
    ]
    descriptions = [
        "Production is down, customers are failing, the SLA is at risk.",
        "Housekeeping; nobody is waiting on it.",
    ]
    efforts = [1, 30, 600]

    blocked_scores: list[float] = []
    startable_scores: list[float] = []
    for tier, deadline, description, effort, blocked in product(
        TIERS, deadlines, descriptions, efforts, (True, False)
    ):
        result = MODEL.score(
            make_task(
                tier=tier,
                deadline=deadline,
                description=description,
                effort_estimate=effort,
                blocked=blocked,
            ),
            CONTEXT,
        )
        (blocked_scores if blocked else startable_scores).append(result.score)

    assert max(blocked_scores) < min(startable_scores)
    assert max(blocked_scores) <= BLOCKED_CEILING
    assert min(startable_scores) >= UNBLOCKED_FLOOR


def test_blocked_tasks_are_still_ordered_among_themselves() -> None:
    """Blocked is a band, not a 0.0: a critical blocked task outranks a trivial one."""
    critical = MODEL.score(
        make_task(
            blocked=True,
            deadline=NOW + timedelta(hours=1),
            description="A customer production system is down and the SLA is at risk.",
        ),
        CONTEXT,
    ).score
    trivial = MODEL.score(
        make_task(blocked=True, deadline=None, description="Housekeeping; nobody is waiting."),
        CONTEXT,
    ).score

    assert critical > trivial > 0.0


# --- the output is always a valid score --------------------------------------


def test_score_stays_within_zero_and_one_at_the_extremes() -> None:
    """Including inputs no scenario would contain — the contract still has to hold."""
    extremes = product(
        TIERS,
        [None, NOW - timedelta(days=3650), NOW + timedelta(days=3650)],
        ["x", "outage sla corrupt customer escalat board investor production churn risk"],
        [1, 100_000],
        (True, False),
        (None, "task_parent"),
    )
    for tier, deadline, description, effort, blocked, parent in extremes:
        result = MODEL.score(
            make_task(
                tier=tier,
                description=description,
                deadline=deadline,
                effort_estimate=effort,
                blocked=blocked,
                parent_task_id=parent,
            ),
            CONTEXT,
        )

        assert 0.0 <= result.score <= 1.0


# --- the lexicons read words, not letter sequences ---------------------------
#
# The phrases are matched with a word boundary, so an impact keyword cannot be
# collected from inside an unrelated word. This is not cosmetic: "board" fired
# inside "dashboard" and "onboarding", which handed +0.15 impact to three of the
# deliberately low-stakes tasks in the persona set, i.e. to exactly the tasks the
# ranking has to push down.

NEUTRAL = "A task with no particular signals in its wording."


@pytest.mark.parametrize(
    "wording",
    [
        "Start the order-history dashboard widget",
        "Refresh the onboarding wiki page",
        "Replace the keyboard shortcuts help",
        "Post it on the team whiteboard",
        "Answer the question in slack",
        "Translate the error messages",
    ],
)
def test_impact_keyword_does_not_fire_inside_a_longer_word(wording: str) -> None:
    """Both "board" and "sla" are whole words; dashboard and slack are not about them."""
    assert impact(make_task(title=wording, description=NEUTRAL)) == pytest.approx(
        impact(make_task(title="Do the thing", description=NEUTRAL))
    )


@pytest.mark.parametrize("wording", ["Prepare the board deck", "The SLA expires at noon"])
def test_impact_keyword_still_fires_as_a_whole_word(wording: str) -> None:
    """The word boundary removes the false matches without removing the real one."""
    assert impact(make_task(title=wording, description=NEUTRAL)) > impact(
        make_task(title="Do the thing", description=NEUTRAL)
    )


@pytest.mark.parametrize(
    "wording",
    [
        "Support escalated it this morning",
        "Two customers reported it",
        "The regulators asked for it",
        "Repeated outages this week",
        "Our investors are asking",
        "A competitor announced it",
        "The job is corrupting rows",
    ],
)
def test_stem_entries_still_match_longer_words(wording: str) -> None:
    """Entries marked `*` are prefixes on purpose and must keep their inflections."""
    assert impact(make_task(title=wording, description=NEUTRAL)) > impact(
        make_task(title="Do the thing", description=NEUTRAL)
    )
