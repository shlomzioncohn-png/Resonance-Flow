from typing import List, Dict

def score_extraction(predicted: List[str], ground_truth: List[str]) -> Dict[str, float]:
    """Computes precision and recall for extracted tasks against ground truth."""
    pred_set = set(predicted)
    truth_set = set(ground_truth)

    if not pred_set:
        return {"precision": 0.0, "recall": 0.0}

    true_positives = len(pred_set.intersection(truth_set))

    precision = true_positives / len(pred_set)
    recall = true_positives / len(truth_set) if truth_set else 0.0

    return {
        "precision": round(precision, 4),
        "recall": round(recall, 4)
    }