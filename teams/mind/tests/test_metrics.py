from mind.evaluation.metrics import score_extraction

def test_score_extraction_perfect_match():
    predicted = ["task A", "task B"]
    ground_truth = ["task A", "task B"]
    result = score_extraction(predicted, ground_truth)

    assert result["precision"] == 1.0
    assert result["recall"] == 1.0

def test_score_extraction_partial_match():
    predicted = ["task A", "task B", "hallucination"]
    ground_truth = ["task A", "task B", "missed_task"]
    result = score_extraction(predicted, ground_truth)

    # 2 of 3 predicted are correct = 0.6667
    assert result["precision"] == 0.6667
    # 2 of 3 actual tasks were found = 0.6667
    assert result["recall"] == 0.6667

def test_score_extraction_empty_predicted():
    predicted = []
    ground_truth = ["task A"]
    result = score_extraction(predicted, ground_truth)

    assert result["precision"] == 0.0
    assert result["recall"] == 0.0

def test_score_extraction_empty_ground_truth():
    predicted = ["task A"]
    ground_truth = []
    result = score_extraction(predicted, ground_truth)

    assert result["precision"] == 0.0
    assert result["recall"] == 0.0
