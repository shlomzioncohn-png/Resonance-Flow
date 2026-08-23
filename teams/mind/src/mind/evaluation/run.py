import argparse
import json
import sys
import os
import importlib.util

def load_scenarios(personas_path: str):
    """Loads MOCK_SCENARIOS from a fixtures.py file inside the given personas directory."""
    fixtures_path = os.path.join(personas_path, "fixtures.py")
    if not os.path.isfile(fixtures_path):
        raise FileNotFoundError(f"No fixtures.py found under --personas path: {personas_path}")

    spec = importlib.util.spec_from_file_location("fixtures", fixtures_path)
    fixtures_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures_module)

    if not hasattr(fixtures_module, "MOCK_SCENARIOS"):
        raise AttributeError(f"{fixtures_path} does not define MOCK_SCENARIOS")

    return fixtures_module.MOCK_SCENARIOS

from mind.evaluation.dummy_model import DummyRankingModel

def evaluate(model, scenarios):

    """Runs a ranking model against a set of scenarios and returns reports of the results."""
    correct = 0
    total = len(scenarios)
    scenario_results = []

    for scenario in scenarios:
        scored_tasks = []
        for task in scenario["tasks"]:
            score = model.score(task)
            scored_tasks.append((task, score))

        if not scored_tasks:
            continue

        best_task, highest_score = max(scored_tasks, key=lambda x: x[1])
        is_correct = best_task.get("expected_top", False)

        if is_correct:
            correct += 1

        scenario_results.append({
            "scenario_id": scenario.get("id"),
            "role": scenario.get("role"),
            "passed": is_correct,
            "predicted_top_task": best_task.get("id"),
            "highest_score": highest_score
        })

    accuracy = (correct / total) if total > 0 else 0.0
    return {
        "model_tested": model.__class__.__name__,
        "total_scenarios": total,
        "correct_top_1": correct,
        "overall_top1_accuracy": accuracy,
        "scenario_results": scenario_results
    }

def main():
    parser = argparse.ArgumentParser(description="Run evaluation harness on ranking models.")
    parser.add_argument("--model", type=str, default="dummy", help="Model to evaluate. (e.g. dummy)")
    parser.add_argument(
        "--personas", type=str, default="eval/personas",
        help="Path to the personas directory (must contain fixtures.py with MOCK_SCENARIOS)."
    )
    args = parser.parse_args()

    if args.model == "dummy":
        model = DummyRankingModel()
    else:
        raise ValueError(f"Unknown model: {args.model}")

    scenarios = load_scenarios(args.personas)
    report = evaluate(model, scenarios)

    print("\n--- Evaluation Report ---")
    print(json.dumps(report, indent=4))
    print("-------------------------\n")

    with open('report.json', 'w') as f:
        json.dump(report, f, indent=4)

if __name__ == "__main__":
    main()