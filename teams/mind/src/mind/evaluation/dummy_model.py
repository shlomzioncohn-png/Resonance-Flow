from mind.evaluation.interfaces import Task


class DummyModel:
    """A dummy ranking model used to verify the harness runs end-to-end."""

    def score(self, task: Task) -> float:
        return 1.0
