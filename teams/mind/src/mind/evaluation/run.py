import argparse
import json
import sys
from datetime import UTC, datetime
from functools import partial
from pathlib import Path

from pydantic import ValidationError

from mind.evaluation.baseline_models import (
    LongestDescriptionModel,
    NearestDeadlineModel,
    SkipBlockedNearestDeadlineModel,
)
from mind.evaluation.baseline_record import (
    BASELINE_EXISTS_MESSAGE,
    BaselineRecordError,
    Provenance,
    build_baseline_record,
    write_baseline_record,
)
from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import EvaluationOutput, Report, run, split_by_role, subset_report
from mind.evaluation.hierarchy import (
    NODES_FILENAME,
    validate_hierarchy_fixture,
    validate_hierarchy_links,
)
from mind.evaluation.interfaces import PersonaEvaluationScenario, RankingModel
from mind.evaluation.personas import (
    HIERARCHY_DECIDES_GROUP,
    HIERARCHY_DIR,
    PersonaSchemaError,
    load_personas,
    validate_personas,
)
from mind.scoring.hierarchy import load_hierarchy


def _summary_lines(label: str, report: Report) -> str:
    return (
        f"{label}\n"
        f"  Scenarios evaluated: {report.total_scenarios}\n"
        f"  Top-1 accuracy:      {report.top1_accuracy:.1%} "
        f"({report.correct_top1}/{report.total_scenarios})"
    )


def _id_in(scenario: PersonaEvaluationScenario, ids: set[str]) -> bool:
    return scenario.scenario_id in ids


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
             "'nearest-deadline', 'skip-blocked-nearest-deadline' or 'longest-description' "
             "(the cheap baselines from eval/personas/README.md's 'Cheap-baseline scores' table).",
    )
    parser.add_argument(
        "--personas", type=str, default="eval/personas",
        help="Path to the personas directory. Contains scenario.schema.json and the "
             "scenario groups (same_tier/, cross_tier/). Loading recurses into the groups; "
             "pass a single group directory to evaluate just that group.",
    )
    parser.add_argument(
        "--hierarchy", type=Path, default=HIERARCHY_DIR, metavar="DIR",
        help="Directory holding nodes.json, the parent nodes the scenarios' "
             "parent_task_id values point at (default: eval/hierarchy). Loaded and "
             "checked against the scenarios before scoring; with --record-baseline "
             "it is part of the recorded set's checksum.",
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
    parser.add_argument(
        "--record-baseline", type=Path, default=None, metavar="PATH",
        help="Also write the score, labelled with --model-commit and "
             "--persona-set-commit, to PATH (e.g. "
             "eval/baselines/baseline_score_<date>.json). Fails if PATH already "
             "exists. See the 'Baseline score' section in the README.",
    )
    parser.add_argument(
        "--model-commit", type=str, default=None, metavar="SHA",
        help="With --record-baseline (required there): the commit holding the "
             "scored model's code.",
    )
    parser.add_argument(
        "--persona-set-commit", type=str, default=None, metavar="SHA",
        help="With --record-baseline (required there): the last commit that "
             "touched the persona set (git log -1 --format=%%H -- eval/personas "
             "eval/hierarchy).",
    )
    args = parser.parse_args()
    if args.record_baseline is None and (args.model_commit or args.persona_set_commit):
        parser.error("--model-commit / --persona-set-commit only apply with --record-baseline")

    models: dict[str, RankingModel] = {
        "dummy": DummyModel(),
        "nearest-deadline": NearestDeadlineModel(),
        "skip-blocked-nearest-deadline": SkipBlockedNearestDeadlineModel(),
        "longest-description": LongestDescriptionModel(),
    }
    try:
        model = models[args.model]
    except KeyError:
        raise ValueError(f"Unknown model: {args.model!r}. Choices: {sorted(models)}") from None

    personas_path = Path(args.personas)

    # Checked before scoring, so a baseline run that can't be recorded fails fast.
    provenance: Provenance | None = None
    if args.record_baseline is not None:
        try:
            if args.model_commit is None or args.persona_set_commit is None:
                raise BaselineRecordError(
                    "--record-baseline needs both --model-commit and --persona-set-commit"
                )
            if args.record_baseline.exists():
                raise BaselineRecordError(
                    BASELINE_EXISTS_MESSAGE.format(path=args.record_baseline)
                )
            provenance = Provenance(
                model_commit=args.model_commit,
                persona_set_commit=args.persona_set_commit,
            )
        except BaselineRecordError as e:
            print(f"Cannot record baseline: {e}", file=sys.stderr)
            sys.exit(1)

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

    # The hierarchy is part of the scored set: a broken node file or a parent_task_id
    # that names no node fails the run, the same way a broken scenario does.
    hierarchy_dir: Path = args.hierarchy
    try:
        fixture = load_hierarchy(hierarchy_dir / NODES_FILENAME)
    except (OSError, UnicodeDecodeError, ValidationError) as e:
        print(f"Could not load the hierarchy from {str(hierarchy_dir)!r}: {e}", file=sys.stderr)
        sys.exit(1)
    hierarchy_errors = validate_hierarchy_fixture(fixture) + validate_hierarchy_links(
        all_scenarios, fixture
    )
    if hierarchy_errors:
        print("\n--- Hierarchy validation FAILED ---")
        for error in hierarchy_errors:
            print(f"  - {error}")
        print("-----------------------------------\n")
        sys.exit(1)

    # One report per scenario group (a subdirectory of the personas dir), plus a combined one.
    group_dirs = sorted(p for p in personas_path.iterdir() if p.is_dir())
    group_scenarios = {group_dir.name: load_personas(group_dir) for group_dir in group_dirs}
    combined_report = run(model, all_scenarios)
    # Each group's report is derived from combined_report rather than re-run, so it's
    # exact even for a non-deterministic model (group_reports, original_report and
    # role_reports below all build on combined_report instead of calling run() again).
    group_ids = {
        name: {s.scenario_id for s in scenarios} for name, scenarios in group_scenarios.items()
    }
    group_reports: dict[str, Report] = {
        name: subset_report(all_scenarios, combined_report, partial(_id_in, ids=ids))
        for name, ids in group_ids.items()
    }
    # The set as it was before the hierarchy group (#507): every other group together.
    # all_scenarios (not group_scenarios) so a loose scenario file directly under
    # personas_path still counts.
    original_report: Report | None = None
    if HIERARCHY_DECIDES_GROUP in group_scenarios:
        hierarchy_ids = {s.scenario_id for s in group_scenarios[HIERARCHY_DECIDES_GROUP]}
        original_report = subset_report(
            all_scenarios, combined_report, lambda s: s.scenario_id not in hierarchy_ids
        )
    # Per role, so a model that is bad for one role can't hide inside the aggregate.
    role_reports = split_by_role(all_scenarios, combined_report)

    print("\n=== Evaluation Summary ===")
    print(f"Model: {combined_report.model_name}\n")
    for name, report in group_reports.items():
        print(_summary_lines(f"[{name}]", report))
        print()
    for role, report in role_reports.items():
        print(_summary_lines(f"[role: {role}]", report))
        print()
    if original_report is not None:
        print(_summary_lines(f"[original set: all but {HIERARCHY_DECIDES_GROUP}]", original_report))
        print()
    print(_summary_lines("[combined]", combined_report))
    print("===========================\n")

    output = EvaluationOutput(
        model_name=combined_report.model_name,
        combined=combined_report,
        groups=group_reports,
        roles=role_reports,
        original_set=original_report,
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

    # Written last, after both accuracy gates: a run the project's own CI treats as a
    # regression must not leave a (write-once, therefore blocking) record behind.
    if args.record_baseline is not None and provenance is not None:
        record = build_baseline_record(
            recorded_on=datetime.now(UTC).date(),
            provenance=provenance,
            personas_path=personas_path,
            hierarchy_dir=hierarchy_dir,
            scenarios=all_scenarios,
            combined=combined_report,
            groups=group_reports,
            roles=role_reports,
        )
        try:
            write_baseline_record(args.record_baseline, record)
        except BaselineRecordError as e:
            print(f"Cannot record baseline: {e}", file=sys.stderr)
            sys.exit(1)
        print(f"Baseline recorded to {args.record_baseline} (commit it).\n")


if __name__ == "__main__":
    main()
