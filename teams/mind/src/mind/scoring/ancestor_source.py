"""#474: where a task's ancestor values come from -- the harness's
constructed graphs (#473) now, Chain's traversal API in production.

Two sources, by necessity, not by choice: the harness has the whole
`HierarchyFixture` in memory and no scale problem (its `importance` values
are already Mind's own notion of importance, hand-picked for the fixtures),
so it walks the chain directly and returns each node's raw value unchanged.
Production has neither -- Inbox's events never carry `parent_task_id`, and
Chain's `/ancestors` has no `importance` field, only its own unrelated
`score` -- see `scoring/README.md`'s "Where an ancestor's importance comes
from" for why `ChainAncestorSource` re-scores each ancestor's own cached
state with the same baseline this model already uses, rather than reading
another task's already-propagated result: averaging does not compose
recursively the way summing an undiminished total once did, so there is no
shortcut that reads just the direct parent's cached score (see
hierarchical_model.py's module docstring).
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Protocol

from mind.evaluation.interfaces import ScoreContext
from mind.scoring.chain_hierarchy_client import ChainHierarchyClient
from mind.scoring.hierarchy import HierarchyFixture
from mind.scoring.inbox_task import InboxTask
from mind.scoring.inputs import ScorableTask
from mind.scoring.segmented_model import SegmentedRankingModel

logger = logging.getLogger("mind.scoring")


class CycleError(ValueError):
    """A task's `parent_task_id` chain loops back on itself (#473's 06_cycle.json).

    Chain's own traversal never surfaces one (it silently truncates instead),
    so only `FixtureAncestorSource`, walking an arbitrary constructed graph,
    can ever raise this.
    """


class AncestorSource(Protocol):
    """What `HierarchicalRankingModel` averages with a task's own score."""

    def ancestor_values(self, task: ScorableTask, context: ScoreContext) -> list[float]:
        """Each of `task`'s ancestors' own importance, nearest-first -- empty
        for a root, an orphan, or one whose ancestors are all unknown. Never
        raises except `CycleError`."""
        ...


class FixtureAncestorSource:
    """Walks `parent_task_id` through an in-memory `HierarchyFixture` (#473),
    collecting each ancestor's own `importance` unchanged -- the README's
    "no depth decay" decision, and no re-scoring: a fixture node's
    `importance` already *is* its own value, by construction, not something
    derived from further applying this model to it. Used by the evaluation
    harness; never by the live path, which has no local fixture to walk."""

    def __init__(self, fixture: HierarchyFixture) -> None:
        self._by_id = fixture.by_id()

    def ancestor_values(self, task: ScorableTask, context: ScoreContext) -> list[float]:
        values: list[float] = []
        seen = {task.task_id}
        current = task.parent_task_id
        while current is not None:
            if current in seen:
                raise CycleError(
                    f"{task.task_id}: parent_task_id chain loops back to {current!r}"
                )
            seen.add(current)
            node = self._by_id.get(current)
            if node is None:
                # Dangling: not what 06_cycle.json tests, and not possible in
                # the eval's own fixture (validate_hierarchy_fixture forbids
                # it) -- treated as "nothing more above this point", the same
                # as reaching a real root.
                break
            values.append(node.importance)
            current = node.parent_task_id
        return values


class ChainAncestorSource:
    """Asks Chain for the whole ancestor chain (`/ancestors` already returns
    it, nearest-first), then for each one re-scores its own last-known state
    with `baseline` to get its own, un-propagated value -- never Mind's
    already-hierarchy-adjusted score for it, and never Chain's own, unrelated
    `score`. An ancestor this process has not seen an Inbox event for since
    start-up (the same cold-start gap `ScorePublisher.rescore` already
    documents), or whose own score raises, is left out of the average rather
    than counted as 0 -- 0 is not what either case means, and would pull
    every such task's score down for no real reason. Deliberately not
    cached: an ancestor's urgency genuinely depends on the current time,
    and a cache keyed on its own fields would freeze that between edits
    for however long nobody happens to touch it.
    """

    def __init__(
        self,
        client: ChainHierarchyClient,
        baseline: SegmentedRankingModel,
        cached_task: Callable[[str], InboxTask | None],
    ) -> None:
        self._client = client
        self._baseline = baseline
        self._cached_task = cached_task

    def ancestor_values(self, task: ScorableTask, context: ScoreContext) -> list[float]:
        values: list[float] = []
        for ancestor_id in self._client.get_ancestors(task.task_id):
            ancestor_task = self._cached_task(ancestor_id)
            if ancestor_task is None:
                logger.warning(
                    "scoring: ancestor %s of task %s is unknown since start-up, "
                    "excluding it from the mean",
                    ancestor_id,
                    task.task_id,
                )
                continue
            try:
                result = self._baseline.score(ancestor_task, context)
            except Exception:
                # A bug scoring this one ancestor must not blank out every
                # task under it -- same treatment as "unknown since start-up".
                logger.exception(
                    "scoring: ancestor %s could not be scored, excluding it from the mean",
                    ancestor_id,
                )
                continue
            values.append(result.base_score_or_score())
        return values
