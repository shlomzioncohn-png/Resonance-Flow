from mind.evaluation.metrics import score_extraction

def test_score_extraction_perfect_match():
    # המודל זיהה בדיוק את מה שהיה צריך
    predicted = ["task A", "task B"]
    ground_truth = ["task A", "task B"]
    result = score_extraction(predicted, ground_truth)
    
    assert result["precision"] == 1.0
    assert result["recall"] == 1.0

def test_score_extraction_partial_match():
    # המודל חילץ 3 משימות, אבל רק 2 מהן נכונות
    # ובנוסף הוא פספס משימה אחת שהייתה באמת
    predicted = ["task A", "task B", "hallucination"]
    ground_truth = ["task A", "task B", "missed_task"]
    result = score_extraction(predicted, ground_truth)
    
    # 2 מתוך 3 שחולצו הן נכונות = 0.6667
    assert result["precision"] == 0.6667
    # 2 מתוך 3 שקיימות במציאות נמצאו = 0.6667
    assert result["recall"] == 0.6667

def test_score_extraction_empty_predicted():
    # המודל לא הצליח לחלץ כלום
    predicted = []
    ground_truth = ["task A"]
    result = score_extraction(predicted, ground_truth)
    
    assert result["precision"] == 0.0
    assert result["recall"] == 0.0

def test_score_extraction_empty_ground_truth():
    # המודל חילץ משימה, אבל בפועל לא היו משימות בטקסט (זיהוי שווא)
    predicted = ["task A"]
    ground_truth = []
    result = score_extraction(predicted, ground_truth)
    
    assert result["precision"] == 0.0
    assert result["recall"] == 0.0