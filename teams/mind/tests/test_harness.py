import pytest
from pydantic import ValidationError

from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import CandidateScore, Report, run
from mind.evaluation.interfaces import ScoreContext, ScoreFactors, ScoreResult, Task
from mind.evaluation.placeholder_scenarios import PLACEHOLDER_SCENARIOS


class _ExplainingModel:
    """Returns reasoning + factors derived from the task, to check the harness carries them.

    Keeps every result it returns, so a test can check the report holds a copy.
    """

    def __init__(self) -> None:
        self.results: dict[str, ScoreResult] = {}

    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        result = ScoreResult(
            score=0.5,
            reasoning=f"why {task.task_id}",
            factors=ScoreFactors(
                contributions={"impact": 0.25}, blocked=task.blocked, base_score=0.5
            ),
        )
        self.results[task.task_id] = result
        return result


def test_run_returns_valid_report():
    report = run(DummyModel(), PLACEHOLDER_SCENARIOS)

    assert isinstance(report, Report)
    assert report.total_scenarios == len(PLACEHOLDER_SCENARIOS)
    assert 0.0 <= report.top1_accuracy <= 1.0
    assert len(report.scenario_results) == report.total_scenarios


def test_scenario_result_keeps_all_candidate_scores():
    report = run(DummyModel(), PLACEHOLDER_SCENARIOS)

    for result, scenario in zip(report.scenario_results, PLACEHOLDER_SCENARIOS):
        assert len(result.candidates) == len(scenario.tasks)


def test_candidates_carry_the_models_reasoning_and_factors():
    report = run(_ExplainingModel(), PLACEHOLDER_SCENARIOS)

    for result, scenario in zip(report.scenario_results, PLACEHOLDER_SCENARIOS, strict=True):
        for candidate, task in zip(result.candidates, scenario.tasks, strict=True):
            assert candidate.task_id == task.task_id
            assert candidate.reasoning == f"why {task.task_id}"
            assert candidate.factors == ScoreFactors(
                contributions={"impact": 0.25}, blocked=task.blocked, base_score=0.5
            )


def test_candidate_factors_are_a_copy_not_the_models_object():
    model = _ExplainingModel()
    report = run(model, PLACEHOLDER_SCENARIOS[:1])

    for candidate in report.scenario_results[0].candidates:
        factors = model.results[candidate.task_id].factors
        assert factors is not None
        assert candidate.factors is not None
        assert candidate.factors is not factors
        assert candidate.factors.contributions is not factors.contributions


def test_candidate_factors_are_none_when_the_model_sets_none():
    report = run(DummyModel(), PLACEHOLDER_SCENARIOS)

    assert all(c.factors is None for r in report.scenario_results for c in r.candidates)


def test_report_round_trips_through_json():
    # A report read back from JSON (e.g. a recorded baseline) is the same report,
    # with factors re-validated into ScoreFactors.
    report = run(_ExplainingModel(), PLACEHOLDER_SCENARIOS)

    assert Report.model_validate_json(report.model_dump_json()) == report


def test_loading_a_report_validates_its_candidates():
    candidate = {"task_id": "task_a", "score": 0.5, "reasoning": None}

    with pytest.raises(ValidationError):
        CandidateScore.model_validate({**candidate, "score": float("nan")})
    with pytest.raises(ValidationError):
        CandidateScore.model_validate({**candidate, "surprise": 1})
    with pytest.raises(ValidationError):
        CandidateScore.model_validate({**candidate, "factors": {"contributions": {}}})
