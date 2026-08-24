from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.interfaces import RankingModel, Task


def test_dummy_model_implements_ranking_model():
    assert isinstance(DummyModel(), RankingModel)


def test_dummy_model_score_returns_float():
    task = Task(
        task_id="task_A",
        title="Review PR",
        description="Code review for the auth refactor",
        estimated_duration_minutes=20,
        tier="Operational",
        blocked=False,
    )
    score = DummyModel().score(task)
    assert isinstance(score, float)
