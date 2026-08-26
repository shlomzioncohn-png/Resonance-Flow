import argparse
import dataclasses
import json
import sys

from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import run
from mind.evaluation.personas import PersonaSchemaError, load_personas, validate_personas


def main():
    parser = argparse.ArgumentParser(description="Run evaluation harness on ranking models.")
    parser.add_argument("--model", type=str, default="dummy", help="Model to evaluate. (e.g. dummy)")
    parser.add_argument(
        "--personas", type=str, default="eval/personas",
        help="Path to the personas directory. Must contain scenario.schema.json plus the "
             "scenario *.json files it validates against.",
    )
    args = parser.parse_args()

    if args.model == "dummy":
        model = DummyModel()
    else:
        raise ValueError(f"Unknown model: {args.model}")

    try:
        scenarios = load_personas(args.personas)
    except (FileNotFoundError, PersonaSchemaError) as e:
        print(f"Could not load personas from {args.personas!r}: {e}", file=sys.stderr)
        sys.exit(1)

    validation = validate_personas(scenarios)
    for warning in validation.warnings:
        print(f"[persona set warning] {warning}")
    if not validation.ok:
        print("\n--- Persona set validation FAILED ---")
        for error in validation.errors:
            print(f"  - {error}")
        print("--------------------------------------\n")
        sys.exit(1)

    report = run(model, scenarios)
    report_dict = dataclasses.asdict(report)

    print("\n=== Evaluation Summary ===")
    print(f"Model:               {report.model_name}")
    print(f"Scenarios evaluated: {report.total_scenarios}")
    print(f"Top-1 accuracy:      {report.top1_accuracy:.1%} ({report.correct_top1}/{report.total_scenarios})")
    print("===========================\n")

    print("--- Evaluation Report (full detail, also written to report.json) ---")
    print(json.dumps(report_dict, indent=4))
    print("----------------------------------------------------------------------\n")

    with open("report.json", "w") as f:
        json.dump(report_dict, f, indent=4)


if __name__ == "__main__":
    main()
