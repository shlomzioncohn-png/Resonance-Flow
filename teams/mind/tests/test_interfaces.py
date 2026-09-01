from datetime import datetime

import pytest
from pydantic import ValidationError

from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.interfaces import (
    PersonaEvaluationScenario,
    RankingModel,
    ScoreContext,
    ScoreResult,
    Task,
)


def test_dummy_model_implements_ranking_model():
    assert isinstance(DummyModel(), RankingModel)


def test_dummy_model_score_returns_score_result_in_range():
    task = Task(
        task_id="task_a",
        title="Review PR",
        description="Code review for the auth refactor",
        effort_estimate=20,
        tier="operational",
        blocked=False,
    )
    context = ScoreContext(current_datetime=datetime.fromisoformat("2026-08-23T14:00:00"))
    result = DummyModel().score(task, context)
    assert isinstance(result, ScoreResult)
    assert 0.0 <= result.score <= 1.0


def _scenario_kwargs(**overrides):
    kwargs = {
        "scenario_id": "developer_late_pr_review",
        "persona": {"role": "Developer"},
        "context": {
            "free_time_minutes": 30,
            "current_datetime": "2026-08-23T14:00:00",
            "time_horizon": "today",
        },
        "tasks": [
            {
                "task_id": "task_a",
                "title": "Doc",
                "description": "Long-form design doc",
                "effort_estimate": 120,
                "tier": "strategic",
                "blocked": False,
            },
            {
                "task_id": "task_b",
                "title": "Review PR #482",
                "description": "Auth refactor review, deadline today",
                "effort_estimate": 20,
                "tier": "operational",
                "blocked": False,
            },
        ],
        "expected_top_task_id": "task_b",
        "expected_reason": "Task b has a same-day deadline and Task a has none, so Task b is the pick.",
        "source": {"type": "synthetic"},
        "team_lead_approved": False,
        "mentor_approved": False,
    }
    kwargs.update(overrides)
    return kwargs


def test_scenario_model_accepts_a_well_formed_scenario():
    scenario = PersonaEvaluationScenario.model_validate(_scenario_kwargs())
    assert scenario.tasks[1].task_id == "task_b"
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


def test_scenario_model_rejects_duplicate_task_ids():
    kwargs = _scenario_kwargs()
    kwargs["tasks"][1]["task_id"] = kwargs["tasks"][0]["task_id"]
    with pytest.raises(ValidationError, match="unique within a scenario"):
        PersonaEvaluationScenario.model_validate(kwargs)


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


def test_task_parent_task_id_defaults_to_none_when_omitted():
    scenario = PersonaEvaluationScenario.model_validate(_scenario_kwargs())
    assert all(task.parent_task_id is None for task in scenario.tasks)


def test_task_accepts_explicit_parent_task_id():
    kwargs = _scenario_kwargs()
    kwargs["tasks"][0]["parent_task_id"] = "manager_task_42"
    scenario = PersonaEvaluationScenario.model_validate(kwargs)
    assert scenario.tasks[0].parent_task_id == "manager_task_42"
