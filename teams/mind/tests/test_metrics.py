"""Direct unit tests for top1_accuracy.

Until now the metric was only exercised transitively through harness.run() in
test_harness.py, with DummyModel (every task scores 1.0) and PLACEHOLDER_SCENARIOS
whose correct answer happens to be listed first. On a score tie max() returns the
first item, so that path *always* reports 100% — it never checks a "prediction is
wrong" case. These tests cover that gap.
"""

from mind.evaluation.interfaces import PersonaEvaluationScenario
from mind.evaluation.metrics import top1_accuracy


def _scenario(expected_top_task_id: str) -> PersonaEvaluationScenario:
    return PersonaEvaluationScenario.model_validate(
        {
            "scenario_id": f"developer_metric_{expected_top_task_id}",
            "persona": {"role": "Developer"},
            "context": {
                "free_time_minutes": 30,
                "current_datetime": "2026-08-25T09:00:00",
                "time_horizon": "today",
            },
            "tasks": [
                {
                    "task_id": "task_a",
                    "title": "A",
                    "description": "Task A",
                    "effort_estimate": 10,
                    "tier": "operational",
                    "blocked": False,
                },
                {
                    "task_id": "task_b",
                    "title": "B",
                    "description": "Task B",
                    "effort_estimate": 10,
                    "tier": "operational",
                    "blocked": False,
                },
            ],
            "expected_top_task_id": expected_top_task_id,
            "expected_reason": "Chosen for a concrete, checkable reason at least twenty chars.",
            "source": {"type": "synthetic"},
            "mentor_approved": False,
        }
    )


def test_empty_input_is_zero():
    assert top1_accuracy([], []) == 0.0


def test_all_predictions_correct_is_one():
    scenarios = [_scenario("task_a"), _scenario("task_b")]
    assert top1_accuracy(["task_a", "task_b"], scenarios) == 1.0


def test_all_predictions_wrong_is_zero():
    scenarios = [_scenario("task_a"), _scenario("task_b")]
    assert top1_accuracy(["task_b", "task_a"], scenarios) == 0.0


def test_half_right_half_wrong_is_one_half():
    # The exact case the DummyModel + fixture-ordering path can never produce.
    scenarios = [_scenario("task_a"), _scenario("task_b")]
    assert top1_accuracy(["task_a", "task_a"], scenarios) == 0.5
