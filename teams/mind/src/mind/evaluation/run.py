import argparse
import json
import sys
from pathlib import Path

from mind.evaluation.baseline_models import (
    NearestDeadlineModel,
    SkipBlockedNearestDeadlineModel,
)
from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import EvaluationOutput, Report, run
from mind.evaluation.interfaces import RankingModel
from mind.evaluation.personas import PersonaSchemaError, load_personas, validate_personas


def _summary_lines(label: str, report: Report) -> str:
    return (
        f"{label}\n"
        f"  Scenarios evaluated: {report.total_scenarios}\n"
        f"  Top-1 accuracy:      {report.top1_accuracy:.1%} "
        f"({report.correct_top1}/{report.total_scenarios})"
    )


def _report_json(output: EvaluationOutput) -> str:
    """Serialize the report. A NaN/inf anywhere raises here instead of reaching
    report.json as a bare `NaN` — which is not valid JSON (jq reads it as null,
    JavaScript's JSON.parse throws).

    Deliberately `json.dumps(..., allow_nan=False)` and not `model_dump_json()`:
    pydantic's JSON serializer writes NaN as `null` by default, silently."""
    return json.dumps(output.model_dump(), indent=4, allow_nan=False)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run evaluation harness on ranking models.")
    parser.add_argument(
        "--model", type=str, default="dummy",
        help="Model to evaluate: 'dummy' (constant score, plumbing only), "
             "'nearest-deadline' or 'skip-blocked-nearest-deadline' (the cheap "
             "baselines from eval/personas/README.md's 'Cheap-baseline scores' table).",
    )
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
             "fraction (0-1). What that means depends on --model: see the "
             "make eval-ci target in the Makefile for how each model's ceiling "
             "is used and why.",
    )
    args = parser.parse_args()

    models: dict[str, RankingModel] = {
        "dummy": DummyModel(),
        "nearest-deadline": NearestDeadlineModel(),
        "skip-blocked-nearest-deadline": SkipBlockedNearestDeadlineModel(),
    }
    try:
        model = models[args.model]
    except KeyError:
        raise ValueError(f"Unknown model: {args.model!r}. Choices: {sorted(models)}") from None

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

    output = EvaluationOutput(
        model_name=combined_report.model_name,
        combined=combined_report,
        groups=group_reports,
    )

    report_text = _report_json(output)
    print("--- Evaluation Report (full detail, also written to report.json) ---")
    print(report_text)
    print("----------------------------------------------------------------------\n")

    with open("report.json", "w") as f:
        f.write(report_text)

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
            f"{combined_report.model_name} combined top-1 accuracy {accuracy:.1%} "
            f"is above --max-accuracy {args.max_accuracy:.1%}.",
            file=sys.stderr,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()
