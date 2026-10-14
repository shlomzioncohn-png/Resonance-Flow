from mind.evaluation.interfaces import ScoreContext, ScoreResult, Task


class DummyModel:
    """A dummy ranking model used to verify the harness runs end-to-end.

    Returns a constant score for every task, so the harness ranking falls back to
    task order — useful only for exercising the plumbing, not as a baseline.
    """

    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        return ScoreResult(score=1.0)
