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
import math
import re
from dataclasses import dataclass

from mind.evaluation.interfaces import ScoreContext, ScoreFactors, ScoreResult, Tier
from mind.scoring import features
from mind.scoring.factors import Factor
from mind.scoring.inputs import ScorableTask
from mind.scoring.keywords import (
    IMPACT_BASE,
    IMPACT_MATCHERS,
    IMPACT_PHRASES,
    STEM_MARKER,
    URGENCY_MATCHERS,
    URGENCY_PHRASES,
)

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
#: across the tiers (.65 > .40 > .21) because deadline proximity is "the
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


#: Every rule's base score is exactly this when all its features sit at 0.5,
#: because each rule's weights sum to 1.0. So `base - NEUTRAL` is the sum of
#: every factor's `weight * (value - NEUTRAL)`: how far that factor moved the
#: score. The explanation is built from those shifts.
NEUTRAL = 0.5
#: A factor whose shift is more than this, either way, is named as a reason.
#: It is the most `quick_win` can ever move a score (its largest weight, 0.10,
#: times 0.5), and the comparison is strict, so the tie-breaker never
#: qualifies: a reason is something that moved the score further than it can.
REASON_SHIFT = 0.05
#: The verdict, symmetric around NEUTRAL: high at or above, low below.
HIGH_BASE_SCORE = 0.6
LOW_BASE_SCORE = 0.4

#: Deadlines this far out or further are given in days rather than hours.
_HOURS_IN_DAYS_FROM = 48

_NO_DEADLINE = "no deadline given"
_NO_ESTIMATE = "no effort estimate"


@dataclass(frozen=True)
class Explanation:
    """Why a task scored the way it did, and which factors the text names.

    `factors` is the structured side: every factor the sentence talks about, in
    the order it first does. `shifts` is what each of this tier's factors moved
    the base score by, away from NEUTRAL; they add up to `base - NEUTRAL`. The
    text is rendered from the same pieces, so a test can hold the two against
    the score's `contributions` without parsing English.
    """

    text: str
    factors: tuple[Factor, ...]
    shifts: dict[Factor, float]


@dataclass(frozen=True)
class _Evaluation:
    """Everything one score is computed from, so the text can be built from it too."""

    tier: Tier
    values: dict[Factor, float]
    contributions: dict[Factor, float]
    base: float


class SegmentedRankingModel:
    """Scores one task at a time, using its tier's rule. Implements `RankingModel`."""

    def score(self, task: ScorableTask, context: ScoreContext) -> ScoreResult:
        if task.tier is None:
            # Carries the id because a task is re-scored on every update: the
            # null-tier rate is distinct ids, not lines. Rare, and the
            # assumption is enough; common, and a separate call for tier may pay.
            logger.info(
                "scoring: tier_assumed - task %s has no tier, scored by the %s rule",
                task.task_id,
                rule_tier(task),
            )
        evaluation = self._evaluate(task, context)
        base = evaluation.base

        if task.blocked:
            final = BLOCKED_CEILING * base
        else:
            final = UNBLOCKED_FLOOR + (1.0 - UNBLOCKED_FLOOR) * base

        return ScoreResult(
            score=min(1.0, max(0.0, final)),
            reasoning=self._explain(task, context, evaluation).text,
            factors=ScoreFactors(
                contributions={
                    factor.value: value for factor, value in evaluation.contributions.items()
                },
                blocked=task.blocked,
                base_score=base,
            ),
        )

    def explain(self, task: ScorableTask, context: ScoreContext) -> Explanation:
        """The explanation `score` puts in `reasoning`, with the factors it names."""
        return self._explain(task, context, self._evaluate(task, context))

    @staticmethod
    def _evaluate(task: ScorableTask, context: ScoreContext) -> _Evaluation:
        tier = rule_tier(task)
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
        return _Evaluation(tier, values, contributions, sum(contributions.values()))

    @staticmethod
    def _explain(task: ScorableTask, context: ScoreContext, evaluation: _Evaluation) -> Explanation:
        """A fixed template over the score's own factors — never generated by a model.

        Every reason is read from the values the score was computed from, and
        only for factors in this tier's rule, so the text cannot name something
        the score did not use. The same task gives the same sentence on every run.
        """
        rule = TIER_RULES[evaluation.tier]
        shifts = {
            factor: weight * (evaluation.values[factor] - NEUTRAL)
            for factor, weight in rule.weights.items()
        }
        # Largest shift first; ties keep the rule's own order.
        order = sorted(rule.weights, key=lambda f: -abs(shifts[f]))

        def is_reason(factor: Factor) -> bool:
            # No estimate withholds a bonus: stated as a gap, never as a reason.
            if factor is Factor.QUICK_WIN and task.effort_estimate is None:
                return False
            return abs(shifts[factor]) > REASON_SHIFT

        def leaning(sign: float) -> list[Factor]:
            # The small shifts that added up to a high or low verdict when none
            # qualified on its own — never the tie-breaker.
            return [f for f in order if f is not Factor.QUICK_WIN and shifts[f] * sign > 0]

        lifting = [f for f in order if is_reason(f) and shifts[f] > 0]
        holding = [f for f in order if is_reason(f) and shifts[f] < 0]

        # The verdict is on the base score, so it says "scores", never "ranks":
        # it is this task's own score, not its place in a list — a list's top
        # task can score in the middle when its rivals score lower. A blocked
        # task's final score is squeezed below every startable one, so for it
        # the verdict is conditional.
        verb = "would score" if task.blocked else "scores"
        decisive = not LOW_BASE_SCORE <= evaluation.base < HIGH_BASE_SCORE
        if evaluation.base >= HIGH_BASE_SCORE:
            lifting = lifting or leaning(+1.0)
            head = f"{verb} high: "
            clauses = [(lifting, ""), (holding, "; against it: ")]
        elif evaluation.base < LOW_BASE_SCORE:
            holding = holding or leaning(-1.0)
            head = f"{verb} low: "
            clauses = [(holding, ""), (lifting, "; in its favour: ")]
        else:
            head = f"{verb} in the middle"
            clauses = [(lifting, "; in its favour: "), (holding, "; against it: ")]
        named = [factor for factors, _ in clauses for factor in factors]

        # Phrases are built only for the factors the sentence names.
        wording = _urgency_phrase(task, context, rule)
        quoted = wording if Factor.TIME_PRESSURE in named else None
        phrases: dict[Factor, str] = {}
        for factor in named:
            if factor is Factor.TIME_PRESSURE:
                phrases[factor] = _time_pressure_phrase(task, context, wording)
            elif factor is Factor.IMPACT:
                phrases[factor] = _impact_phrase(task, evaluation.values[factor], quoted)
            else:
                phrases[factor] = f"it takes about {task.effort_estimate} minutes"

        missing: list[tuple[Factor, str]] = []
        # Stated unless the sentence already says it — including when the
        # wording, not the absent date, set the time pressure.
        says_no_deadline = Factor.TIME_PRESSURE in named and wording is None
        if task.deadline is None and not says_no_deadline:
            missing.append((Factor.TIME_PRESSURE, _NO_DEADLINE))
        if Factor.QUICK_WIN in rule.weights and task.effort_estimate is None:
            missing.append((Factor.QUICK_WIN, _NO_ESTIMATE))

        parts = [
            lead + "; ".join(phrases[factor] for factor in factors)
            for factors, lead in clauses
            if factors
        ]
        if decisive and not clauses[0][0]:
            # Unreachable with today's weights (some non-effort shift always
            # leans the verdict's way), kept so the sentence never reads empty.
            parts.insert(0, "no single factor stands out")
        elif not named:
            parts = [": no factor stands out"]
        body = head + "".join(parts)
        if missing:
            body += "; " + "; ".join(phrase for _, phrase in missing)

        # The task's own tier when it has one. When it has none, say so and
        # name the rule that was assumed: never print the tier as "None", and
        # never present an assumed tier as the task's.
        if task.tier is None:
            subject = f"task of unknown tier, scored as {evaluation.tier}"
        else:
            subject = f"{task.tier} task"
        sentence = f"{subject}, {body}"
        if task.blocked:
            sentence += "; blocked, so it cannot outrank anything startable"

        in_order = named + [factor for factor, _ in missing]
        return Explanation(f"{sentence}.", tuple(dict.fromkeys(in_order)), shifts)


def _urgency_phrase(task: ScorableTask, context: ScoreContext, rule: TierRule) -> str | None:
    """The phrase that set the time pressure, when the wording rather than the date did.

    `time_pressure` is the max of the two, so naming the deadline when the
    wording won would explain the score with a number that did not set it.
    """
    dated = features.deadline_proximity(
        task.deadline, context.current_datetime, rule.scale_hours, rule.missing_deadline
    )
    stated = features.stated_urgency(task)
    if stated <= dated:
        return None
    text = features.task_text(task)
    # The phrase that set the max, not merely the first one present.
    for phrase, (pattern, weight) in zip(URGENCY_PHRASES, URGENCY_MATCHERS, strict=True):
        if weight == stated and (match := pattern.search(text)):
            return _as_written(text, phrase, match)
    raise AssertionError("no urgency phrase matched the value it was asked to explain")


def _time_pressure_phrase(task: ScorableTask, context: ScoreContext, wording: str | None) -> str:
    if wording is not None:
        return f'its wording says it is urgent now ("{wording}")'
    if task.deadline is None:
        return _NO_DEADLINE
    hours = (task.deadline - context.current_datetime).total_seconds() / 3600
    if hours <= 0:
        return "the deadline has passed"
    if hours < 1:
        return "the deadline is less than an hour away"
    if hours < _HOURS_IN_DAYS_FROM:
        return f"the deadline is {_count(_round_half_up(hours), 'hour')} away"
    return f"the deadline is {_count(_round_half_up(hours / 24), 'day')} away"


def _round_half_up(x: float) -> int:
    """Halves always round up: Python's `round` sends 2.5 to 2 but 3.5 to 4."""
    return math.floor(x + 0.5)


def _count(n: int, unit: str) -> str:
    return f"{n} {unit}" if n == 1 else f"{n} {unit}s"


def _impact_phrase(task: ScorableTask, value: float, quoted: str | None) -> str:
    """Name the words that moved impact, in the direction the value moved.

    Only called for a named impact, so the value is off IMPACT_BASE and at
    least one word moved it that way. A phrase the time pressure already
    quoted is not repeated.
    """
    text = features.task_text(task)
    raised = value >= IMPACT_BASE
    words = [
        _as_written(text, phrase, match)
        for phrase, (pattern, weight) in zip(IMPACT_PHRASES, IMPACT_MATCHERS, strict=True)
        if (weight > 0) == raised and (match := pattern.search(text))
    ]
    if not raised:
        return f"its wording plays it down ({_quoted(words)})"
    words = [word for word in words if word != quoted]
    return f"it mentions {_quoted(words)}" if words else "that wording also raises its impact"


def _quoted(words: list[str]) -> str:
    """Lexicon phrases are quoted, as the urgency phrase is: many are not nouns
    ("needs your", "must reach"), and bare they would not read as a sentence."""
    return ", ".join(f'"{word}"' for word in words)


def _as_written(text: str, phrase: str, match: re.Match[str]) -> str:
    """The phrase as the task wrote it: a stem is printed as the whole word.

    `"escalat*"` matched "escalated" should read "escalated", not "escalat".
    The text is the lowercased blob the lexicon read, so the word is too.
    """
    if not phrase.endswith(STEM_MARKER):
        return phrase
    word = re.match(r"\w+", text[match.start() :])
    return word.group() if word else phrase.removesuffix(STEM_MARKER)
