<<<<<<< HEAD
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
=======
import pytest

from mind.evaluation.metrics import extraction_precision_recall


def test_extraction_precision_recall_perfect_match():
    predicted = ["task A", "task B"]
    ground_truth = ["task A", "task B"]
    result = extraction_precision_recall(predicted, ground_truth)

    assert result["precision"] == 1.0
    assert result["recall"] == 1.0


def test_extraction_precision_recall_partial_match():
    predicted = ["task A", "task B", "hallucination"]
    ground_truth = ["task A", "task B", "missed_task"]
    result = extraction_precision_recall(predicted, ground_truth)

    # 2 of 3 predicted are correct
    assert result["precision"] == pytest.approx(2 / 3)
    # 2 of 3 actual tasks were found
    assert result["recall"] == pytest.approx(2 / 3)


def test_extraction_precision_recall_values_not_rounded():
    predicted = ["a", "b", "c"]
    ground_truth = ["a"]
    result = extraction_precision_recall(predicted, ground_truth)

    # full precision, not round(1/3, 4)
    assert result["precision"] == pytest.approx(1 / 3)
    assert result["precision"] != round(1 / 3, 4)


def test_extraction_precision_recall_empty_predicted():
    result = extraction_precision_recall([], ["task A"])

    assert result["precision"] == 0.0
    assert result["recall"] == 0.0


def test_extraction_precision_recall_empty_ground_truth():
    result = extraction_precision_recall(["task A"], [])

    assert result["precision"] == 0.0
    assert result["recall"] == 1.0


def test_extraction_precision_recall_both_empty():
    result = extraction_precision_recall([], [])

    assert result["precision"] == 1.0
    assert result["recall"] == 1.0


def test_extraction_precision_recall_ignores_duplicates():
    # duplicate task strings on either side must not change the score
    predicted = ["task A", "task A", "task B"]
    ground_truth = ["task A", "task B", "task B"]
    result = extraction_precision_recall(predicted, ground_truth)

    assert result["precision"] == 1.0
    assert result["recall"] == 1.0
>>>>>>> michal/metrics-research
