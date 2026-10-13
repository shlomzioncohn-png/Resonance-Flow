"""How far to trust a score, given which of its inputs were missing.

The score sent to Chain carries a confidence (`task.score.proposed`, field 5).
A missing tier or effort estimate does not stop a task being scored — the
model assumes a tier and drops the bonus — but it does make the score less
certain, and this says by how much. Nothing missing is full trust.

Each penalty is the worst a missing field can do to the score, not the
typical: Chain orders tasks by score, a gap of 0.05 already flips an order,
and which tasks are badly off cannot be known in advance. A confidence that
warns too much costs some caution; one that reassures wrongly costs a wrong
order.

Kept apart from the model on purpose: `ScoreResult` is R1's contract and has
no confidence field, and the live path (#357) can call this on its own.
"""

from mind.scoring.factors import Factor
from mind.scoring.inputs import ScorableTask
from mind.scoring.segmented_model import ASSUMED_TIER, TIER_RULES

#: Measured, not derived. How far a task's base score moves when its tier is
#: replaced by the assumed one: the 90th percentile over all 91 persona tasks,
#: 2026-10-13 (mean 0.063, median 0.039, max 0.352). Synthetic tasks, so the
#: spread on real ones may differ; re-measure once #353 produces them.
ASSUMED_TIER_SHIFT = 0.17


def score_confidence(task: ScorableTask) -> float:
    """1.0 for a task with nothing missing, lower for each assumption made."""

    confidence = 1.0

    tier = task.tier
    if tier is None:
        confidence *= 1.0 - ASSUMED_TIER_SHIFT
        tier = ASSUMED_TIER

    if task.effort_estimate is None:
        # Derived, not measured: the bonus cannot move a score by more than
        # its own weight in the rule that was used, and strategic has none.
        confidence *= 1.0 - TIER_RULES[tier].weights.get(Factor.QUICK_WIN, 0.0)

    return confidence
