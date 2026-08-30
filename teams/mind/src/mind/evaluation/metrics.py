def extraction_precision_recall(
    predicted: list[str], ground_truth: list[str]
) -> dict[str, float]:
    """Entity-level precision and recall for extracted tasks vs. ground truth.

    Inputs are treated as sets: duplicate task strings are counted once, so
    repeated mentions of the same task do not affect the score.

    Edge cases:
      - both inputs empty          -> {"precision": 1.0, "recall": 1.0}
      - predicted empty only       -> {"precision": 0.0, "recall": 0.0}
      - ground_truth empty only    -> {"precision": 0.0, "recall": 1.0}

    Values are returned at full precision; round only when displaying results.
    """
    pred_set = set(predicted)
    truth_set = set(ground_truth)

    if not pred_set and not truth_set:
        return {"precision": 1.0, "recall": 1.0}

    true_positives = len(pred_set & truth_set)

    precision = true_positives / len(pred_set) if pred_set else 0.0
    recall = true_positives / len(truth_set) if truth_set else 1.0

    return {"precision": precision, "recall": recall}
