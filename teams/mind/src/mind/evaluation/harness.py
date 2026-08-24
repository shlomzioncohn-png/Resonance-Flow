from dataclasses import dataclass

from mind.evaluation.interfaces import RankingModel, Task
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


def run(model: RankingModel, scenarios: list[dict]) -> Report:
    predictions: list[str] = []
    scenario_results: list[ScenarioResult] = []

    for scenario in scenarios:
        candidates = [Task(**task) for task in scenario["tasks"]]
        if not candidates:
            continue

        scored = [(task, model.score(task)) for task in candidates]
        best_task, _ = max(scored, key=lambda pair: pair[1])

        predictions.append(best_task.task_id)
        scenario_results.append(
            ScenarioResult(
                scenario_id=scenario.get("scenario_id"),
                expected_top_task_id=scenario.get("expected_top_task_id"),
                predicted_top_task_id=best_task.task_id,
                passed=best_task.task_id == scenario.get("expected_top_task_id"),
                candidates=[CandidateScore(task_id=t.task_id, score=s) for t, s in scored],
            )
        )

    accuracy = top1_accuracy(predictions, scenarios)

    return Report(
        model_name=model.__class__.__name__,
        total_scenarios=len(scenarios),
        correct_top1=sum(r.passed for r in scenario_results),
        top1_accuracy=accuracy,
        scenario_results=scenario_results,
    )


# Placeholder scenarios — replace once the real eval/personas/ set (michal/persona-eval-schema) lands.
PLACEHOLDER_SCENARIOS: list[dict] = [
    {
        "scenario_id": "developer_junior_placeholder_1",
        "expected_top_task_id": "task_A",
        "tasks": [
            {
                "task_id": "task_A",
                "title": "Fix failing CI test",
                "description": "A unit test regressed on main and is blocking every PR.",
                "estimated_duration_minutes": 15,
                "tier": "Operational",
                "blocked": False,
                "dependencies": [],
            },
            {
                "task_id": "task_B",
                "title": "Write design doc for Q3 feature",
                "description": "Draft architecture doc for a feature planned for next quarter.",
                "estimated_duration_minutes": 120,
                "tier": "Tactical",
                "blocked": False,
                "dependencies": [],
            },
        ],
    },
    {
        "scenario_id": "manager_placeholder_1",
        "expected_top_task_id": "task_C",
        "tasks": [
            {
                "task_id": "task_C",
                "title": "Unblock teammate waiting on approval",
                "description": "A PR is stuck waiting for the manager's review, blocking a teammate.",
                "estimated_duration_minutes": 10,
                "tier": "Tactical",
                "blocked": False,
                "dependencies": [],
            },
            {
                "task_id": "task_D",
                "title": "Long-term roadmap planning",
                "description": "Quarterly roadmap planning session prep.",
                "estimated_duration_minutes": 180,
                "tier": "Strategic",
                "blocked": False,
                "dependencies": [],
            },
        ],
    },
]
