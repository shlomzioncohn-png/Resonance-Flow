import argparse
from harness.dummy_model import DummyRankingModel
from harness.fixtures import MOCK_SCENARIOS

def evaluate(model, scenarios):

    """Runs a ranking model against a set of scenarios and returns reports of the results."""
    correct = 0
    total = len(scenarios)

    for scenario in scenarios:
        scored_tasks = []
        for task in scenario["tasks"]:
            score = model.score(task)
            scored_tasks.append((task, score))

        if scored_tasks:
            correct += 1

    accuracy = (correct / total) if total > 0 else 0.0
    return {
        "total_scenarios": total,
        "correct_top_1": correct,
        "accuracy": accuracy
    }

def main():
    parser =  argparse.ArgumentParser(description="Run evaluateion harness on ranking models.")
    parser.add_argument("--model", type=str, default="dummy", help="Model to evaluate. (e.g. dummy)")
    args = parser.parse_args()

    if args.model == "dummy":
        model = DummyRankingModel()
    else:
        raise ValueError(f"Unknown model: {args.model}")

    report = evaluate(model, MOCK_SCENARIOS)
    print("\n--- Evaluation Report ---")
    print(f"Total Scenarios : {report['total_scenarios']}")
    print(f"Top-1 Accuracy  : {report['accuracy']:.2f}")
    print("-------------------------\n")

if __name__ == "__main__":
    main()