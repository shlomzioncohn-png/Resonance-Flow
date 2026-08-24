import argparse
import dataclasses
import importlib.util
import json
import os

from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import PLACEHOLDER_SCENARIOS, run


def load_scenarios(personas_path: str) -> list[dict]:
    """Loads MOCK_SCENARIOS from a fixtures.py file inside the given personas directory.

    Falls back to the harness's placeholder scenarios if eval/personas/ doesn't
    exist yet (the real persona set lands in weeks 2-3).
    """
    fixtures_path = os.path.join(personas_path, "fixtures.py")
    if not os.path.isfile(fixtures_path):
        return PLACEHOLDER_SCENARIOS

    spec = importlib.util.spec_from_file_location("fixtures", fixtures_path)
    fixtures_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures_module)

    if not hasattr(fixtures_module, "MOCK_SCENARIOS"):
        raise AttributeError(f"{fixtures_path} does not define MOCK_SCENARIOS")

    return fixtures_module.MOCK_SCENARIOS


def main():
    parser = argparse.ArgumentParser(description="Run evaluation harness on ranking models.")
    parser.add_argument("--model", type=str, default="dummy", help="Model to evaluate. (e.g. dummy)")
    parser.add_argument(
        "--personas", type=str, default="eval/personas",
        help="Path to the personas directory (must contain fixtures.py with MOCK_SCENARIOS). "
             "Falls back to a small internal placeholder set if missing.",
    )
    args = parser.parse_args()

    if args.model == "dummy":
        model = DummyModel()
    else:
        raise ValueError(f"Unknown model: {args.model}")

    scenarios = load_scenarios(args.personas)
    report = run(model, scenarios)
    report_dict = dataclasses.asdict(report)

    print("\n--- Evaluation Report ---")
    print(json.dumps(report_dict, indent=4))
    print("-------------------------\n")

    with open("report.json", "w") as f:
        json.dump(report_dict, f, indent=4)


if __name__ == "__main__":
    main()
