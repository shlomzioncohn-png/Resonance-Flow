import argparse
import dataclasses
import json
import sys
from pathlib import Path

from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import Report, run
from mind.evaluation.personas import PersonaSchemaError, load_personas, validate_personas


def _summary_lines(label: str, report: Report) -> str:
    return (
        f"{label}\n"
        f"  Scenarios evaluated: {report.total_scenarios}\n"
        f"  Top-1 accuracy:      {report.top1_accuracy:.1%} "
        f"({report.correct_top1}/{report.total_scenarios})"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Run evaluation harness on ranking models.")
    parser.add_argument("--model", type=str, default="dummy", help="Model to evaluate. (e.g. dummy)")
    parser.add_argument(
        "--personas", type=str, default="eval/personas",
        help="Path to the personas directory. Contains scenario.schema.json and the "
             "scenario groups (same_tier/, cross_tier/). Loading recurses into the groups; "
             "pass a single group directory to evaluate just that group.",
    )
    parser.add_argument(
        "--min-accuracy", type=float, default=None,
        help="If set, exit 1 when the combined top-1 accuracy falls below this "
             "fraction (0-1) — a regression gate for CI.",
    )
    parser.add_argument(
        "--max-accuracy", type=float, default=None,
        help="If set, exit 1 when the combined top-1 accuracy rises above this "
             "fraction (0-1) — catches the scenario set drifting back toward the "
             "no-headroom baseline the 2026-09-06 rebuild fixed (see eval/personas/README.md).",
    )
    args = parser.parse_args()

    if args.model == "dummy":
        model = DummyModel()
    else:
        raise ValueError(f"Unknown model: {args.model}")

    personas_path = Path(args.personas)

    try:
        all_scenarios = load_personas(personas_path)
    except (FileNotFoundError, PersonaSchemaError) as e:
        print(f"Could not load personas from {args.personas!r}: {e}", file=sys.stderr)
        sys.exit(1)

    # Coverage / duplicate / not-ready checks run against the whole set, not per group.
    validation = validate_personas(all_scenarios)
    for warning in validation.warnings:
        print(f"[persona set warning] {warning}")
    if not validation.ok:
        print("\n--- Persona set validation FAILED ---")
        for error in validation.errors:
            print(f"  - {error}")
        print("--------------------------------------\n")
        sys.exit(1)

    # One report per scenario group (a subdirectory of the personas dir), plus a combined one.
    group_dirs = sorted(p for p in personas_path.iterdir() if p.is_dir())
    group_reports: dict[str, Report] = {
        group_dir.name: run(model, load_personas(group_dir)) for group_dir in group_dirs
    }
    combined_report = run(model, all_scenarios)

    print("\n=== Evaluation Summary ===")
    print(f"Model: {combined_report.model_name}\n")
    for name, report in group_reports.items():
        print(_summary_lines(f"[{name}]", report))
        print()
    print(_summary_lines("[combined]", combined_report))
    print("===========================\n")

    output = {
        "model_name": combined_report.model_name,
        "combined": dataclasses.asdict(combined_report),
        "groups": {name: dataclasses.asdict(r) for name, r in group_reports.items()},
    }

    print("--- Evaluation Report (full detail, also written to report.json) ---")
    print(json.dumps(output, indent=4))
    print("----------------------------------------------------------------------\n")

    with open("report.json", "w") as f:
        json.dump(output, f, indent=4)

    accuracy = combined_report.top1_accuracy
    if args.min_accuracy is not None and accuracy < args.min_accuracy:
        print(
            f"Combined top-1 accuracy {accuracy:.1%} is below --min-accuracy "
            f"{args.min_accuracy:.1%} — regression.",
            file=sys.stderr,
        )
        sys.exit(1)
    if args.max_accuracy is not None and accuracy > args.max_accuracy:
        print(
            f"Combined top-1 accuracy {accuracy:.1%} is above --max-accuracy "
            f"{args.max_accuracy:.1%} — the scenario set no longer has headroom "
            f"(see 'Cheap-baseline scores' in eval/personas/README.md).",
            file=sys.stderr,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()
