"""Cheap heuristic baselines, used to gate the persona set's headroom in CI.

Unlike `DummyModel` (constant score, exercises only the harness plumbing),
these actually read `task.deadline` and `task.blocked` -- the same two-line
heuristics documented in the "Cheap-baseline scores" table in
eval/personas/README.md. `make eval-ci` runs `SkipBlockedNearestDeadlineModel`
and fails if its combined accuracy rises too close to 100%: that is what
happened to the pre-2026-09-06 26-scenario set (85%, no headroom), and
`DummyModel` alone cannot detect it because DummyModel never looks at
`deadline` or `blocked` in the first place.

`LongestDescriptionModel` guards a different failure: a set whose correct answers
are, by construction, the tasks with the longest description (they carry the
explanation of why they are correct), so a model can score well without reading
the content at all. It reads nothing but the text length.
"""

from mind.evaluation.interfaces import ScoreContext, ScoreResult, Task


class NearestDeadlineModel:
    """Sooner deadline = higher score. Ignores `blocked`."""

    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        if task.deadline is None:
            return ScoreResult(score=0.01)
        hours = (task.deadline - context.current_datetime).total_seconds() / 3600
        return ScoreResult(score=min(0.99, max(0.02, 0.5 - hours / 10_000)))


class SkipBlockedNearestDeadlineModel(NearestDeadlineModel):
    """Same, but a blocked task sinks below everything else."""

    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        if task.blocked:
            return ScoreResult(score=0.0)
        return super().score(task, context)


class LongestDescriptionModel:
    """Longer description = higher score. Reads nothing else.

    A length-bias probe, not a model anyone would ship: if it scores well on the
    persona set, the set rewards length instead of understanding."""

    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        length = len(task.description)
        return ScoreResult(score=length / (length + 1000))
