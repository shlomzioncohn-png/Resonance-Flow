"""The segmented-ranking baseline: one scoring rule per tier, one 0-1 scale.

Why segmented at all: under a single shared formula a strategic goal with no
deadline always loses to an urgent operational ticket, which is precisely the
failure this system exists to fix. `tier` is therefore **not** a base score and
not an urgency level — it is a switch that selects which yardstick a task is
measured with. All three yardsticks report onto the same 0-1 scale, so tasks of
different tiers are still comparable within a scenario.

Deliberately deterministic: no LLM, no clock reads, no randomness. The same task
scores identically on every run, which is what makes a week-5 baseline
comparable to a week-7 stretch.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from mind.evaluation.interfaces import ScoreContext, ScoreFactors, ScoreResult, Tier
from mind.scoring import features
from mind.scoring.factors import Factor
from mind.scoring.inputs import ScorableTask

#: A blocked task's score is squeezed into [0, BLOCKED_CEILING] and an
#: unblocked one into [UNBLOCKED_FLOOR, 1]. Because the ceiling is strictly
#: below the floor, **a blocked task can never outrank an unblocked one**, at
#: any tier, whatever its deadline — the guarantee `tests/test_scoring.py`
#: pins down.
#:
#: Two bands rather than a hard 0.0: "blocked" means *not startable right now*,
#: not *worthless*. Zeroing every blocked task makes them indistinguishable, and
#: deciding that something is undoable is Chain's call, not MIND's ("MIND
#: scores, it does not decide"). Ordering is kept inside the blocked band so a
#: consumer can still tell a critical blocked task from a forgotten one, and so
#: a scenario where *everything* is blocked still ranks sensibly instead of
#: collapsing into a tie broken by file order.
BLOCKED_CEILING = 0.25
UNBLOCKED_FLOOR = 0.30


@dataclass(frozen=True)
class TierRule:
    """One tier's yardstick: its sense of time, and what it weighs.

    `weights` must sum to 1.0 so a rule's weighted sum is already inside 0-1
    with no clamping: **the tier changes the weights, not the range.** Bands per
    tier were rejected deliberately — they would decide in advance that one kind
    of task always beats another, which is the very thing being tested.

    The real requirement is on the **reachable** range, not on the sum. A weight
    parked on a feature that can never leave 0 passes the sum check and still
    shortens the scale: that is exactly what the removed `initiative` weight did
    to this rule, which could only ever reach 0.95 while the other two reached
    1.0 — quietly penalising strategic tasks wherever tiers compete directly.
    `test_every_rule_can_reach_a_full_base_score` is what holds this now.
    """

    scale_hours: float
    missing_deadline: float
    weights: dict[Factor, float]


#: Weight rationale, in three strengths of justification (see README):
#:
#: *Derived from the issue's tier table* — the ordering. Deadline weight falls
#: across the tiers (.65 > .40 > .20) because deadline proximity is "the
#: dominant signal" operationally, "matters but less sharply" tactically and
#: "matters least" strategically; impact rises (.25 < .55 < .79) as its mirror.
#: The two cross exactly once, between operational and tactical — that crossing
#: *is* the segmentation. At operational, .65 > .35 makes deadline dominant in
#: the strong sense: it outweighs everything else combined.
#:
#: *Bounded by argument* — quick_win is capped at .10 and drops to .05, because
#: `effort_estimate` is Chain's input and a correct answer may never hinge on
#: it; at these weights it can only break a near-tie. It is dropped entirely at
#: strategic, where a 40-minute item is not a "quick win" in any useful sense.
#:
#: *Not here at all* — hierarchy. The strategic rule briefly carried a .05
#: `initiative` weight on "does this task have a parent". It was removed at the
#: team lead's request: in a real company almost every task sits under one, so
#: the signal is close to information-free, and it was never measured anyway —
#: `parent_task_id` was null in every scenario, so the feature always returned
#: 0 and the .05 was reasoned, not calibrated. Keeping the baseline ignorant of
#: hierarchy is also what makes week 7's propagation stretch measurable: the
#: comparison answers "did hierarchy help" only if the baseline never saw it.
#: Its .05 was redistributed between the two remaining weights in proportion
#: (.20/.95 and .75/.95), so neither was re-chosen.
#:
#: *Calibrated on the dev split only* — the operational and tactical decimals,
#: and every scale_hours/missing_deadline value. Chosen against the 20 dev
#: scenarios of the original 20/10 split; the set is 36 and the split 24/12
#: since #535, and no number here was revisited against the larger one. No
#: holdout scenario was used to choose any of them.
#:
#: *Derived, not calibrated* — the strategic pair, .21 and .79. They are
#: .20/.95 and .75/.95, the renormalisation described above, and they were
#: never fitted to anything: the dev result is identical before and after the
#: change that produced them (20/24 either way).
TIER_RULES: dict[Tier, TierRule] = {
    # Hours are the unit of the working day: a deadline 8 hours out is half-pressing.
    # No deadline reads as low pressure — operational work that nobody dated is
    # usually genuinely not pressing — but `stated_urgency` can still override it.
    "operational": TierRule(
        scale_hours=8.0,
        missing_deadline=0.15,
        weights={Factor.TIME_PRESSURE: 0.65, Factor.IMPACT: 0.25, Factor.QUICK_WIN: 0.10},
    ),
    # Days, not hours: tactical work is planned across a week.
    # No deadline is mildly negative, not damning.
    "tactical": TierRule(
        scale_hours=72.0,
        missing_deadline=0.30,
        weights={Factor.TIME_PRESSURE: 0.40, Factor.IMPACT: 0.55, Factor.QUICK_WIN: 0.05},
    ),
    # Weeks. No deadline is read as *neutral* (0.5), not as "not urgent": most
    # real strategic work carries no date, so the absence says nothing either way.
    "strategic": TierRule(
        scale_hours=336.0,
        missing_deadline=0.50,
        weights={Factor.TIME_PRESSURE: 0.21, Factor.IMPACT: 0.79},
    ),
}

#: The rule a task with no tier is scored by. A missing tier is a real state,
#: not an error: extraction leaves it null when the content gives no sense of
#: timescale (#351). Tactical is the middle of both axes — 72 scale_hours
#: between 8 and 336, weights between the other two — so it assumes the least.
#: The tier itself stays null everywhere else; only the choice of rule is made.
ASSUMED_TIER: Tier = "tactical"

logger = logging.getLogger("mind.scoring")


def rule_tier(task: ScorableTask) -> Tier:
    """The tier whose rule scores this task: its own, or the assumed one.

    The one place that decision is made. The model scores by it and
    `score_confidence` charges by it, so the two cannot drift apart.
    """
    return ASSUMED_TIER if task.tier is None else task.tier


#: How each factor reads in a one-line explanation.
_FACTOR_PHRASING: dict[Factor, str] = {
    Factor.TIME_PRESSURE: "how soon this has to happen",
    Factor.IMPACT: "who is affected and how badly",
    Factor.QUICK_WIN: "how quickly it can be cleared",
}


class SegmentedRankingModel:
    """Scores one task at a time, using its tier's rule. Implements `RankingModel`."""

    def score(self, task: ScorableTask, context: ScoreContext) -> ScoreResult:
        tier = rule_tier(task)
        if task.tier is None:
            # Carries the id because a task is re-scored on every update: the
            # null-tier rate is distinct ids, not lines. Rare, and the
            # assumption is enough; common, and a separate call for tier may pay.
            logger.info(
                "scoring: tier_assumed - task %s has no tier, scored by the %s rule",
                task.task_id,
                tier,
            )
        rule = TIER_RULES[tier]
        values = {
            Factor.TIME_PRESSURE: features.time_pressure(
                task, context.current_datetime, rule.scale_hours, rule.missing_deadline
            ),
            Factor.IMPACT: features.impact(task),
            Factor.QUICK_WIN: features.quick_win(task),
        }

        # Only this tier's features contribute; the others are not part of its
        # rule at all, and their absence from `contributions` is what shows that.
        contributions = {factor: weight * values[factor] for factor, weight in rule.weights.items()}
        base = sum(contributions.values())

        if task.blocked:
            final = BLOCKED_CEILING * base
        else:
            final = UNBLOCKED_FLOOR + (1.0 - UNBLOCKED_FLOOR) * base

        return ScoreResult(
            score=min(1.0, max(0.0, final)),
            reasoning=self._reasoning(task, tier, contributions),
            factors=ScoreFactors(
                contributions={factor.value: value for factor, value in contributions.items()},
                blocked=task.blocked,
                base_score=base,
            ),
        )

    @staticmethod
    def _reasoning(task: ScorableTask, rule_tier: Tier, contributions: dict[Factor, float]) -> str:
        """A fixed template over the factors — never generated by a model.

        Phrasing an explanation nicely is issue #358's job in week 6; this
        issue's job is to make the factors it will need exist and stay stable
        between runs.
        """
        leading = max(contributions, key=lambda factor: contributions[factor])
        # The task's own tier when it has one. When it has none, say so and
        # name the rule that was assumed: never print the tier as "None", and
        # never present an assumed tier as the task's.
        if task.tier is None:
            subject = f"task of unknown tier, scored as {rule_tier}"
        else:
            subject = f"{task.tier} task"
        sentence = f"{subject}, led by {_FACTOR_PHRASING[leading]}"
        if task.blocked:
            return f"{sentence}; blocked, so it cannot outrank anything startable."
        return f"{sentence}."
