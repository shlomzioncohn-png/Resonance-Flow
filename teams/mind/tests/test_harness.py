from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import Report, run
from mind.evaluation.interfaces import ScoreContext, ScoreResult, Task
from mind.evaluation.placeholder_scenarios import PLACEHOLDER_SCENARIOS


class _ExplainingModel:
    """Returns reasoning + factors derived from the task, to check the harness copies them."""

    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        return ScoreResult(
            score=0.5,
            reasoning=f"why {task.task_id}",
            factors={"blocked_penalty": -1.0 if task.blocked else 0.0},
        )


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
            assert candidate.factors == {"blocked_penalty": -1.0 if task.blocked else 0.0}


def test_candidate_factors_are_none_when_the_model_sets_none():
    report = run(DummyModel(), PLACEHOLDER_SCENARIOS)

    assert all(c.factors is None for r in report.scenario_results for c in r.candidates)
