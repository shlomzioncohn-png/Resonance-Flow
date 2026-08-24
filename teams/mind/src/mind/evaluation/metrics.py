def top1_accuracy(predictions: list[str], scenarios: list[dict]) -> float:
    """Fraction of scenarios where the predicted top task matches expected_top_task_id."""
    if not scenarios:
        return 0.0

    correct = sum(
        1
        for predicted, scenario in zip(predictions, scenarios)
        if predicted == scenario.get("expected_top_task_id")
    )
    return correct / len(scenarios)
