import pytest
from pydantic import ValidationError

from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.interfaces import PersonaEvaluationScenario, RankingModel, Task


def test_dummy_model_implements_ranking_model():
    assert isinstance(DummyModel(), RankingModel)


def test_dummy_model_score_returns_float():
    task = Task(
        task_id="task_A",
        title="Review PR",
        description="Code review for the auth refactor",
        estimated_duration_minutes=20,
        tier="operational",
        blocked=False,
    )
    score = DummyModel().score(task)
    assert isinstance(score, float)


def _scenario_kwargs(**overrides):
    kwargs = {
        "scenario_id": "developer_junior_late_pr_review",
        "persona": {"role": "Developer"},
        "context": {
            "free_time_minutes": 30,
            "current_datetime": "2026-08-23T14:00:00",
            "time_horizon": "today",
        },
        "tasks": [
            {
                "task_id": "task_A",
                "title": "Doc",
                "description": "Long-form design doc",
                "estimated_duration_minutes": 120,
                "tier": "strategic",
                "blocked": False,
            },
            {
                "task_id": "task_B",
                "title": "Review PR #482",
                "description": "Auth refactor review, deadline today",
                "estimated_duration_minutes": 20,
                "tier": "operational",
                "blocked": False,
            },
        ],
        "expected_top_task_id": "task_B",
        "expected_reason": "Task B fits the 30-minute window and has a same-day deadline.",
        "source": {"type": "synthetic"},
        "mentor_approved": False,
    }
    kwargs.update(overrides)
    return kwargs


def test_scenario_model_accepts_a_well_formed_scenario():
    scenario = PersonaEvaluationScenario.model_validate(_scenario_kwargs())
    assert scenario.tasks[1].task_id == "task_B"
    # ISO strings are coerced to datetime.
    assert scenario.context.current_datetime.year == 2026


def test_scenario_model_rejects_unknown_field():
    with pytest.raises(ValidationError, match="surprise"):
        PersonaEvaluationScenario.model_validate(_scenario_kwargs(surprise="!"))


def test_scenario_model_rejects_dangling_expected_top_task_id():
    with pytest.raises(ValidationError, match="expected_top_task_id"):
        PersonaEvaluationScenario.model_validate(
            _scenario_kwargs(expected_top_task_id="task_missing")
        )


def test_scenario_model_requires_source_detail_for_ami():
    with pytest.raises(ValidationError, match="source_detail"):
        PersonaEvaluationScenario.model_validate(
            _scenario_kwargs(source={"type": "AMI"})
        )


def test_scenario_model_requires_at_least_two_tasks():
    kwargs = _scenario_kwargs()
    kwargs["tasks"] = kwargs["tasks"][:1]
    with pytest.raises(ValidationError):
        PersonaEvaluationScenario.model_validate(kwargs)
