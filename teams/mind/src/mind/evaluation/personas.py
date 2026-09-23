"""Loads and validates the persona evaluation scenario set (eval/personas/).

Two separate steps, matching the two kinds of rule described in
eval/personas/README.md:

- `load_personas`: every scenario file must parse into a
  `PersonaEvaluationScenario` (mind.evaluation.interfaces) — required fields,
  types, enums, bounds, *and* the intra-scenario invariants enforced by that
  model's `@model_validator`s (a dangling `expected_top_task_id`, a missing
  `source_detail`). A file that doesn't is a bug in that file, not a "maybe" —
  this raises.
- `validate_personas`: the rules a single-scenario model cannot see — a
  `scenario_id` duplicated across the whole directory (a hard error), plus the
  "the set isn't finished yet" checks (a role nobody wrote a scenario for, under/
  over role coverage, an out-of-range total, `mentor_approved: false`). Those
  last ones are reported as warnings, not raised: the set is built up
  incrementally by multiple people before the Foundation Gate, and a mid-week PR
  shouldn't fail CI just because the set isn't complete yet.
"""

from __future__ import annotations

import calendar
import json
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from pathlib import Path

from pydantic import ValidationError

from mind.evaluation.interfaces import PersonaEvaluationScenario, TimeHorizon

# Present in eval/personas/ as a generated artifact (see mind.evaluation.schema);
# skipped when scanning the directory for scenario files.
SCHEMA_FILENAME = "scenario.schema.json"

ALL_ROLES = {
    "Developer",
    "Manager",
    "Higher Manager",
    "Field Engineer",
    "Salesperson",
    "CEO",
}

MIN_SCENARIOS_PER_ROLE = 4
MAX_SCENARIOS_PER_ROLE = 6
MIN_TOTAL_SCENARIOS = 24
MAX_TOTAL_SCENARIOS = 36


class PersonaSchemaError(ValueError):
    """One or more scenario files in the personas directory do not conform to the schema."""


@dataclass
class ValidationReport:
    """Result of validate_personas: hard errors vs. not-ready-yet warnings."""

    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """True if there are no hard errors. Warnings alone do not fail validation."""
        return not self.errors


def load_personas(personas_dir: str | Path) -> list[PersonaEvaluationScenario]:
    """Loads every scenario JSON file under `personas_dir` as a PersonaEvaluationScenario.

    Scenario files are every `*.json` file in the directory tree (searched
    recursively, so `eval/personas/` picks up both the `same_tier/` and
    `cross_tier/` groups) other than the generated `scenario.schema.json`.
    Pass a specific group directory (`eval/personas/same_tier`) to load just
    that group.

    Returns an empty list if the directory has no scenario files yet (the set is
    built up incrementally before the Foundation Gate). Raises FileNotFoundError
    if the directory is missing, and PersonaSchemaError listing every schema
    violation found, across every offending file, if any scenario fails to parse.
    """
    personas_path = Path(personas_dir)
    if not personas_path.is_dir():
        raise FileNotFoundError(f"Personas directory not found: {personas_path}")

    scenarios: list[PersonaEvaluationScenario] = []
    schema_errors: list[str] = []
    for scenario_path in sorted(personas_path.rglob("*.json")):
        if scenario_path.name == SCHEMA_FILENAME:
            continue

        with scenario_path.open(encoding="utf-8") as f:
            raw = json.load(f)

        try:
            scenarios.append(PersonaEvaluationScenario.model_validate(raw))
        except ValidationError as exc:
            for err in exc.errors():
                location = "/".join(str(p) for p in err["loc"]) or "<root>"
                schema_errors.append(f"{scenario_path.name} [{location}]: {err['msg']}")

    if schema_errors:
        raise PersonaSchemaError(
            f"{len(schema_errors)} schema violation(s) in {personas_path}:\n"
            + "\n".join(f"  - {e}" for e in schema_errors)
        )

    return scenarios


def validate_personas(scenarios: list[PersonaEvaluationScenario]) -> ValidationReport:
    """Checks the rules a single PersonaEvaluationScenario cannot enforce on its own.

    See "Constraints not enforced by the scenario model itself" in
    eval/personas/README.md for the source of these rules.
    """
    report = ValidationReport()

    if not scenarios:
        # An empty set is a hard error, not a "not ready yet" warning: it means the
        # personas path is wrong or the files were moved/deleted. "measured nothing"
        # must never pass the same way "measured everything and it was correct" does.
        report.errors.append(
            "no scenarios loaded — the personas directory is empty or the path is wrong"
        )
        return report

    seen_scenario_ids: set[str] = set()
    scenarios_per_role: Counter[str] = Counter()

    for scenario in scenarios:
        if scenario.scenario_id in seen_scenario_ids:
            report.errors.append(f"{scenario.scenario_id}: duplicate scenario_id")
        seen_scenario_ids.add(scenario.scenario_id)

        scenarios_per_role[scenario.persona.role] += 1

        if not scenario.mentor_approved:
            report.warnings.append(f"{scenario.scenario_id}: not yet mentor_approved")

    missing_roles = ALL_ROLES - scenarios_per_role.keys()
    if missing_roles:
        report.warnings.append(
            f"no scenario yet for role(s): {', '.join(sorted(missing_roles))}"
        )

    for role, count in sorted(scenarios_per_role.items()):
        if not (MIN_SCENARIOS_PER_ROLE <= count <= MAX_SCENARIOS_PER_ROLE):
            report.warnings.append(
                f"{role}: has {count} scenario(s), expected "
                f"{MIN_SCENARIOS_PER_ROLE}-{MAX_SCENARIOS_PER_ROLE}"
            )

    total = len(scenarios)
    if total and not (MIN_TOTAL_SCENARIOS <= total <= MAX_TOTAL_SCENARIOS):
        report.warnings.append(
            f"{total} scenario(s) total, expected "
            f"{MIN_TOTAL_SCENARIOS}-{MAX_TOTAL_SCENARIOS}"
        )

    return report


def time_horizon_end(current: datetime, horizon: TimeHorizon) -> datetime:
    """Last instant still considered "inside" the given time_horizon window.

    See "Deadline proximity" in eval/personas/README.md: a scenario's correct
    answer must have its own deadline (if any) inside this window; only a
    distractor's deadline may fall beyond it.
    """
    if horizon == "today":
        return datetime.combine(current.date(), time(23, 59, 59))
    if horizon == "this_week":
        # Monday-start week; end = the Sunday of the current week.
        days_until_sunday = 6 - current.weekday()
        end_date = current.date() + timedelta(days=days_until_sunday)
        return datetime.combine(end_date, time(23, 59, 59))
    if horizon == "this_month":
        last_day = calendar.monthrange(current.year, current.month)[1]
        return datetime.combine(date(current.year, current.month, last_day), time(23, 59, 59))
    raise AssertionError(f"unhandled TimeHorizon: {horizon!r}")


def correct_answer_deadline_violates_time_horizon(
    scenario: PersonaEvaluationScenario,
) -> str | None:
    """Checks one scenario against the time_horizon rule above.

    Returns a human-readable violation message if the scenario's correct
    answer has a deadline that falls after its time_horizon window ends, or
    None if the scenario is fine (including when the correct answer has no
    deadline at all, in which case there is nothing to check).
    """
    correct = next(t for t in scenario.tasks if t.task_id == scenario.expected_top_task_id)
    if correct.deadline is None:
        return None

    end = time_horizon_end(scenario.context.current_datetime, scenario.context.time_horizon)
    if correct.deadline > end:
        return (
            f"{scenario.scenario_id}: time_horizon={scenario.context.time_horizon!r} ends "
            f"{end.isoformat()}, but correct answer {correct.task_id!r} has deadline "
            f"{correct.deadline.isoformat()} (after the window)"
        )
    return None
