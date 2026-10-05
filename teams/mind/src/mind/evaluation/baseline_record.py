"""Record a baseline score to a durable, versioned file that a re-run cannot overwrite.

The baseline is the number the week-7 stretch is measured against (#486), so it
has to say exactly what was scored (the repo commit holding the model, the
persona-set commit) and it must never be silently replaced. `report.json` is
neither: it is gitignored and rewritten on every run.

`python -m mind.evaluation.run --model <name> --record-baseline <path>
--model-commit <sha> --persona-set-commit <sha>` is the entry point; this module
holds the pieces so they can be tested on their own.

The file format is one Pydantic model, `BaselineRecord`, used for both writing
and reading: the file is written in one week and read back weeks later by other
code, so the two sides must not be two separate descriptions of the same format.
Its consistency rules run on every write and every read.
"""

import dataclasses
import re
from datetime import date
from pathlib import Path
from typing import Self

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from mind.evaluation.harness import Report, ScenarioResult
from mind.evaluation.interfaces import PersonaEvaluationScenario

METRIC = "top-1 accuracy"

# A git commit sha, full (40) or abbreviated (at least 7), as `git log` prints it.
# Pydantic's `pattern` uses Rust regex, where `$` is the end of the text; Python's
# `$` also accepts a trailing newline, so `Provenance` uses `fullmatch` instead.
_COMMIT_SHA_PATTERN = r"^[0-9a-f]{7,40}$"
_COMMIT_SHA = re.compile(r"[0-9a-f]{7,40}")

_RECORD_CONFIG = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)


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


class ScoreSummary(BaseModel):
    """Top-1 counts for one slice of the run (combined, a group, or a role)."""

    model_config = _RECORD_CONFIG

    correct_top1: int = Field(ge=0)
    total_scenarios: int = Field(ge=0)
    top1_accuracy: float

    @model_validator(mode="after")
    def _accuracy_matches_counts(self) -> Self:
        # A derived number is never trusted on its own when its inputs sit
        # right next to it: a stored accuracy that disagrees is a corrupt record.
        if self.correct_top1 > self.total_scenarios:
            raise ValueError(
                f"correct_top1 ({self.correct_top1}) is larger than "
                f"total_scenarios ({self.total_scenarios})"
            )
        expected = self.correct_top1 / self.total_scenarios if self.total_scenarios else 0.0
        if self.top1_accuracy != expected:
            raise ValueError(
                f"top1_accuracy is {self.top1_accuracy} but correct_top1/total_scenarios "
                f"is {self.correct_top1}/{self.total_scenarios} = {expected}"
            )
        return self

    @classmethod
    def of(cls, report: Report) -> Self:
        return cls(
            correct_top1=report.correct_top1,
            total_scenarios=report.total_scenarios,
            top1_accuracy=report.top1_accuracy,
        )


class PersonaSetInfo(BaseModel):
    """Which persona set was scored, and its approval state at the time."""

    model_config = _RECORD_CONFIG

    path: str
    commit: str = Field(pattern=_COMMIT_SHA_PATTERN)
    scenarios: int = Field(ge=0)
    team_lead_approved: int = Field(ge=0)
    mentor_approved: int = Field(ge=0)


class BaselineRecord(BaseModel):
    """The baseline file: labels first, then the numbers, then every scenario's
    prediction and candidate scores (the spot-check and the week-7 "which
    recommendation changed" comparison both need the per-scenario detail).

    Every `ScoreSummary` checks its own accuracy against its counts; this model
    checks that `combined` agrees with `scenario_results`.
    """

    model_config = _RECORD_CONFIG

    recorded_on: date
    model_name: str
    model_commit: str = Field(pattern=_COMMIT_SHA_PATTERN)
    metric: str
    persona_set: PersonaSetInfo
    combined: ScoreSummary
    groups: dict[str, ScoreSummary]
    roles: dict[str, ScoreSummary]
    scenario_results: list[ScenarioResult]

    @model_validator(mode="after")
    def _combined_matches_scenario_results(self) -> Self:
        for r in self.scenario_results:
            if r.passed != (r.predicted_top_task_id == r.expected_top_task_id):
                raise ValueError(
                    f"{r.scenario_id} is marked passed={r.passed} but predicted "
                    f"{r.predicted_top_task_id!r} vs expected {r.expected_top_task_id!r}"
                )
        if self.combined.total_scenarios != len(self.scenario_results):
            raise ValueError(
                f"total_scenarios is {self.combined.total_scenarios} but the record "
                f"holds {len(self.scenario_results)} scenario results"
            )
        passed = sum(r.passed for r in self.scenario_results)
        if self.combined.correct_top1 != passed:
            raise ValueError(
                f"correct_top1 is {self.combined.correct_top1} but "
                f"{passed} scenario results are marked passed"
            )
        return self

    def to_report(self) -> Report:
        """The combined `Report` this record was written from."""
        return Report(
            model_name=self.model_name,
            total_scenarios=self.combined.total_scenarios,
            correct_top1=self.combined.correct_top1,
            top1_accuracy=self.combined.top1_accuracy,
            scenario_results=list(self.scenario_results),
        )


def build_baseline_record(
    *,
    recorded_on: date,
    provenance: Provenance,
    personas_path: Path,
    scenarios: list[PersonaEvaluationScenario],
    combined: Report,
    groups: dict[str, Report],
    roles: dict[str, Report],
) -> BaselineRecord:
    """Build the record from a finished run. The record's consistency checks run
    here too, so an inconsistent record is refused before it is ever written."""
    return BaselineRecord(
        recorded_on=recorded_on,
        model_name=combined.model_name,
        model_commit=provenance.model_commit,
        metric=METRIC,
        persona_set=PersonaSetInfo(
            path=personas_path.as_posix(),
            commit=provenance.persona_set_commit,
            scenarios=len(scenarios),
            team_lead_approved=sum(s.team_lead_approved for s in scenarios),
            mentor_approved=sum(s.mentor_approved for s in scenarios),
        ),
        combined=ScoreSummary.of(combined),
        groups={name: ScoreSummary.of(r) for name, r in groups.items()},
        roles={role: ScoreSummary.of(r) for role, r in roles.items()},
        scenario_results=combined.scenario_results,
    )


def write_baseline_record(path: Path, record: BaselineRecord) -> None:
    """Write `record` to `path`, failing if `path` already exists.

    A recorded baseline is never overwritten; a re-run goes to a new, dated file.
    (`model_dump_json` can't write NaN here: every model in the record refuses it.)
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as f:
            f.write(record.model_dump_json(indent=4))
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
    Validated through `BaselineRecord`, so a hand-edited, truncated or
    malformed file is refused with an error naming the field at fault.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        raise BaselineRecordError(f"cannot read baseline record {path}: {e}") from e
    try:
        record = BaselineRecord.model_validate_json(text)
    except ValidationError as e:
        raise BaselineRecordError(f"{path} is not a valid baseline record:\n{e}") from e
    return record.to_report()
