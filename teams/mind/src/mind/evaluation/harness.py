from dataclasses import dataclass

from mind.evaluation.interfaces import PersonaEvaluationScenario, RankingModel
from mind.evaluation.metrics import top1_accuracy


@dataclass
class CandidateScore:
    task_id: str
    score: float


@dataclass
class ScenarioResult:
    scenario_id: str
    expected_top_task_id: str | None
    predicted_top_task_id: str
    passed: bool
    candidates: list[CandidateScore]  # every candidate's score, not just the winner


@dataclass
class Report:
    model_name: str
    total_scenarios: int
    correct_top1: int
    top1_accuracy: float
    scenario_results: list[ScenarioResult]


def run(model: RankingModel, scenarios: list[PersonaEvaluationScenario]) -> Report:
    predictions: list[str] = []
    evaluated_scenarios: list[PersonaEvaluationScenario] = []
    scenario_results: list[ScenarioResult] = []

    for scenario in scenarios:
        candidates = scenario.tasks
        if not candidates:
            continue

        scored = [(task, model.score(task)) for task in candidates]
        best_task, _ = max(scored, key=lambda pair: pair[1])

        predictions.append(best_task.task_id)
        evaluated_scenarios.append(scenario)
        scenario_results.append(
            ScenarioResult(
                scenario_id=scenario.scenario_id,
                expected_top_task_id=scenario.expected_top_task_id,
                predicted_top_task_id=best_task.task_id,
                passed=best_task.task_id == scenario.expected_top_task_id,
                candidates=[CandidateScore(task_id=t.task_id, score=s) for t, s in scored],
            )
        )

    accuracy = top1_accuracy(predictions, evaluated_scenarios)

    return Report(
        model_name=model.__class__.__name__,
        total_scenarios=len(evaluated_scenarios),
        correct_top1=sum(r.passed for r in scenario_results),
        top1_accuracy=accuracy,
        scenario_results=scenario_results,
    )
