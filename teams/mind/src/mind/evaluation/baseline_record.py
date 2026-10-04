"""Record a baseline score to a durable, versioned file that a re-run cannot overwrite.

The baseline is the number the week-7 stretch is measured against (#486), so it
has to say exactly what was scored (the repo commit holding the model, the
persona-set commit) and it must never be silently replaced. `report.json` is
neither: it is gitignored and rewritten on every run.

`python -m mind.evaluation.run --model <name> --record-baseline <path>
--model-commit <sha> --persona-set-commit <sha>` is the entry point; this module
holds the pieces so they can be tested on their own.
"""

import dataclasses
import json
import re
from datetime import date
from pathlib import Path
from typing import Any

from mind.evaluation.harness import CandidateScore, Report, ScenarioResult
from mind.evaluation.interfaces import PersonaEvaluationScenario

METRIC = "top-1 accuracy"

# A git commit sha, full (40) or abbreviated (at least 7), as `git log` prints it.
_COMMIT_SHA = re.compile(r"[0-9a-f]{7,40}")


class BaselineRecordError(RuntimeError):
    """The baseline could not be recorded without losing or misstating something."""


@dataclasses.dataclass(frozen=True)
class Provenance:
    """Which code and which scenarios a recorded score belongs to.

    Both shas are given by whoever records the baseline (`--model-commit`,
    `--persona-set-commit`). They are only checked to *look like* a commit sha:
    that catches an empty value, a branch name or a stray paste, but not a
    swapped pair or a wrong sha, which is why the README gives the exact git
    command for each.
    """

    model_commit: str  # the commit holding the scored model's code
    persona_set_commit: str  # the last commit that touched the persona set

    def __post_init__(self) -> None:
        for name in ("model_commit", "persona_set_commit"):
            value = getattr(self, name)
            if not _COMMIT_SHA.fullmatch(value):
                raise BaselineRecordError(
                    f"{name} {value!r} is not a git commit sha "
                    "(7-40 lowercase hex characters, as `git log` prints it)"
                )


def _summary(report: Report) -> dict[str, Any]:
    return {
        "correct_top1": report.correct_top1,
        "total_scenarios": report.total_scenarios,
        "top1_accuracy": report.top1_accuracy,
    }


def build_baseline_record(
    *,
    recorded_on: date,
    provenance: Provenance,
    personas_path: Path,
    scenarios: list[PersonaEvaluationScenario],
    combined: Report,
    groups: dict[str, Report],
    roles: dict[str, Report],
) -> dict[str, Any]:
    """Build the JSON-ready record: labels first, then the numbers, then every
    scenario's prediction and candidate scores (the spot-check and the week-7
    "which recommendation changed" comparison both need the per-scenario detail).
    """
    return {
        "recorded_on": recorded_on.isoformat(),
        "model_name": combined.model_name,
        "model_commit": provenance.model_commit,
        "metric": METRIC,
        "persona_set": {
            "path": personas_path.as_posix(),
            "commit": provenance.persona_set_commit,
            "scenarios": len(scenarios),
            "team_lead_approved": sum(s.team_lead_approved for s in scenarios),
            "mentor_approved": sum(s.mentor_approved for s in scenarios),
        },
        "combined": _summary(combined),
        "groups": {name: _summary(r) for name, r in groups.items()},
        "roles": {role: _summary(r) for role, r in roles.items()},
        "scenario_results": [r.model_dump() for r in combined.scenario_results],
    }


def write_baseline_record(path: Path, record: dict[str, Any]) -> None:
    """Write `record` to `path`, failing if `path` already exists.

    A recorded baseline is never overwritten; a re-run goes to a new, dated file.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as f:
            json.dump(record, f, indent=4)
            f.write("\n")
    except FileExistsError:
        raise BaselineRecordError(
            f"{path} already exists; a recorded baseline is never overwritten. "
            "Record a re-run to a new, dated file instead."
        ) from None


def load_baseline_record(path: Path) -> Report:
    """Read a recorded baseline back into the combined `Report` it was written from.

    This is what the week-7 comparison (#487) diffs the stretch run against:
    the frozen, committed number, not a fresh re-run of the baseline model.
    Refuses a record whose per-scenario results don't add up to its own
    totals, so a hand-edited or truncated file can't be compared silently.
    """
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
        results = [
            ScenarioResult(
                scenario_id=r["scenario_id"],
                expected_top_task_id=r["expected_top_task_id"],
                predicted_top_task_id=r["predicted_top_task_id"],
                passed=r["passed"],
                candidates=[CandidateScore(**c) for c in r["candidates"]],
            )
            for r in record["scenario_results"]
        ]
        combined = record["combined"]
        report = Report(
            model_name=record["model_name"],
            total_scenarios=combined["total_scenarios"],
            correct_top1=combined["correct_top1"],
            top1_accuracy=combined["top1_accuracy"],
            scenario_results=results,
        )
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as e:
        raise BaselineRecordError(f"{path} is not a readable baseline record: {e!r}") from e

    for r in results:
        if r.passed != (r.predicted_top_task_id == r.expected_top_task_id):
            raise BaselineRecordError(
                f"{path}: {r.scenario_id} is marked passed={r.passed} but predicted "
                f"{r.predicted_top_task_id!r} vs expected {r.expected_top_task_id!r}"
            )
    if report.total_scenarios != len(results):
        raise BaselineRecordError(
            f"{path}: total_scenarios is {report.total_scenarios} but the record "
            f"holds {len(results)} scenario results"
        )
    if report.correct_top1 != sum(r.passed for r in results):
        raise BaselineRecordError(
            f"{path}: correct_top1 is {report.correct_top1} but "
            f"{sum(r.passed for r in results)} scenario results are marked passed"
        )
    return report
