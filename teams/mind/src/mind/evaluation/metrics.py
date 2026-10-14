from mind.evaluation.interfaces import PersonaEvaluationScenario


def extraction_precision_recall(
    predicted: list[str], ground_truth: list[str]
) -> dict[str, float]:
    """Entity-level precision and recall for extracted tasks vs. ground truth.

    **Placeholder — exact string match only.** Real extraction output is almost
    never byte-identical to the annotated sentence, so this scores near-zero on
    real AMI text. The actual extraction-quality eval is
    `extraction_eval.evaluate_extraction` (fuzzy one-to-one matching + F1); this
    function is its `threshold = 1.0`, identity-`similarity` special case and
    stays only for its existing callers and tests.

    Inputs are treated as sets: duplicate task strings are counted once, so
    repeated mentions of the same task do not affect the score.

    Edge cases:
      - both inputs empty          -> {"precision": 1.0, "recall": 1.0      }
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



def top1_accuracy(
    predictions: list[str], scenarios: list[PersonaEvaluationScenario]
) -> float:
    """Fraction of scenarios where the predicted top task matches expected_top_task_id."""
    if not scenarios:
        return 0.0

    # strict=True: a length mismatch between predictions and scenarios is a bug in
    # the caller (harness.run appends one prediction per scenario), and a plain zip
    # would silently truncate to the shorter list and return a quietly wrong number.
    correct = sum(
        1
        for predicted, scenario in zip(predictions, scenarios, strict=True)
        if predicted == scenario.expected_top_task_id
    )
    return correct / len(scenarios)
