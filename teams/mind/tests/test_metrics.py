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
