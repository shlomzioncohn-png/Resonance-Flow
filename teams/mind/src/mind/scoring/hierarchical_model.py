"""#474: hierarchical value propagation, layered over R2's baseline (#354).

`base = 0.5 * own_base + 0.5 * mean(ancestor_values)`, `own_base` unchanged
if there are no ancestors. Averaging, not summing: both operands are already
in [0, 1], so `base` never leaves that range either -- no clamp, no
normalization step, unlike an earlier additive design. Each ancestor's value
is its own raw, un-propagated score, never another task's already-adjusted
one; see `ancestor_source.py` for why and `scoring/README.md`'s "Where an
ancestor's importance comes from" for the full rationale.

The baseline is never modified: this model only calls
`SegmentedRankingModel.score()` for a task's own features and composes that
with `AncestorSource`'s values.
"""

from __future__ import annotations

from mind.evaluation.interfaces import ScoreContext, ScoreFactors, ScoreResult
from mind.scoring.ancestor_source import AncestorSource
from mind.scoring.factors import Factor
from mind.scoring.inputs import ScorableTask
from mind.scoring.segmented_model import SegmentedRankingModel, band

#: own_base's fixed weight in the blend -- the other half is split evenly
#: across however many ancestors a task has, not per-ancestor.
OWN_WEIGHT = 0.5


class HierarchicalRankingModel:
    """Scores one task at a time: the baseline's own features, averaged with
    its ancestors' own values. Implements `RankingModel` (and `TaskScorer`,
    the live path's wider equivalent). Pure: no network, no side effects --
    `ScorePublisher` owns the rescore cascade (#474), triggered by a send
    whose result actually differs, not by this model being called."""

    def __init__(
        self,
        ancestor_source: AncestorSource,
        *,
        baseline: SegmentedRankingModel | None = None,
    ) -> None:
        self._baseline = baseline if baseline is not None else SegmentedRankingModel()
        self._ancestor_source = ancestor_source

    def score(self, task: ScorableTask, context: ScoreContext) -> ScoreResult:
        own = self._baseline.score(task, context)
        own_base = own.base_score_or_score()

        ancestor_values = self._ancestor_source.ancestor_values(task, context)
        if ancestor_values:
            mean_ancestors = sum(ancestor_values) / len(ancestor_values)
            base = OWN_WEIGHT * own_base + (1 - OWN_WEIGHT) * mean_ancestors
        else:
            base = own_base

        final = band(base, task.blocked)

        contributions = dict(own.factors.contributions) if own.factors is not None else {}
        # The net effect of blending in the ancestors -- positive, negative,
        # or 0.0 for a root/orphan, never just "the boost" (an average can
        # pull a task below its own baseline, unlike the old additive sum).
        contributions[Factor.HIERARCHY.value] = base - own_base

        reasoning = own.reasoning
        if ancestor_values:
            if base > own_base:
                direction = "raises"
            elif base < own_base:
                direction = "lowers"
            else:
                direction = "leaves unchanged"
            reasoning = (
                f"{reasoning} Its {len(ancestor_values)} ancestor(s) average "
                f"{mean_ancestors:.2f}, which {direction} its own score."
            )

        return ScoreResult(
            score=min(1.0, max(0.0, final)),
            reasoning=reasoning,
            factors=ScoreFactors(
                contributions=contributions,
                blocked=task.blocked,
                base_score=base,
            ),
        )
