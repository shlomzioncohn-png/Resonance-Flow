"""Loads and validates the persona evaluation scenario set (eval/personas/).

Two separate steps, matching the two kinds of rule described in
eval/personas/README.md:

- `load_personas`: every scenario file must structurally conform to
  scenario.schema.json (required fields, types, enums, ...). A file that
  doesn't is a bug in that file, not a "maybe" — this raises.
- `validate_personas`: the cross-scenario invariants the JSON Schema itself
  cannot express (a dangling `expected_top_task_id`, a duplicate
  `scenario_id`, a role nobody wrote a scenario for yet...). Some of these
  are hard errors; others (missing role coverage, `mentor_approved: false`)
  just mean the set isn't finished yet, so they're reported as warnings
  rather than raised.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import jsonschema

SCHEMA_FILENAME = "scenario.schema.json"

ALL_ROLES = {
    "Developer",
    "Manager",
    "Higher Manager",
    "Field Engineer",
    "Salesperson",
    "CEO",
}

SOURCE_TYPES_REQUIRING_DETAIL = {"AMI", "FUNSD", "other"}


class PersonaSchemaError(ValueError):
    """One or more scenario files in the personas directory violate scenario.schema.json."""


@dataclass
class ValidationReport:
    """Result of validate_personas: hard errors vs. not-ready-yet warnings."""

    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """True if there are no hard errors. Warnings alone do not fail validation."""
        return not self.errors


def load_personas(personas_dir: str | Path) -> list[dict]:
    """Loads every scenario JSON file in `personas_dir`, validated against scenario.schema.json.

    `personas_dir` must contain scenario.schema.json alongside the scenario
    files (that's the real eval/personas/ layout). Scenario files are every
    `*.json` file in the directory other than the schema itself.

    Returns an empty list if the directory has no scenario files yet (the set
    is built up incrementally before the Foundation Gate). Raises
    FileNotFoundError if the directory or the schema file is missing, and
    PersonaSchemaError listing every schema violation found, across every
    offending file, if any scenario fails to validate.
    """
    personas_path = Path(personas_dir)
    if not personas_path.is_dir():
        raise FileNotFoundError(f"Personas directory not found: {personas_path}")

    schema_path = personas_path / SCHEMA_FILENAME
    if not schema_path.is_file():
        raise FileNotFoundError(f"Schema file not found: {schema_path}")

    with schema_path.open(encoding="utf-8") as f:
        schema = json.load(f)

    validator = jsonschema.Draft202012Validator(schema)

    scenarios: list[dict] = []
    schema_errors: list[str] = []
    for scenario_path in sorted(personas_path.glob("*.json")):
        if scenario_path.name == SCHEMA_FILENAME:
            continue

        with scenario_path.open(encoding="utf-8") as f:
            scenario = json.load(f)

        file_errors = sorted(validator.iter_errors(scenario), key=lambda e: e.path)
        if file_errors:
            for error in file_errors:
                location = "/".join(str(p) for p in error.path) or "<root>"
                schema_errors.append(f"{scenario_path.name} [{location}]: {error.message}")
        else:
            scenarios.append(scenario)

    if schema_errors:
        raise PersonaSchemaError(
            f"{len(schema_errors)} schema violation(s) in {personas_path}:\n"
            + "\n".join(f"  - {e}" for e in schema_errors)
        )

    return scenarios


def validate_personas(scenarios: list[dict]) -> ValidationReport:
    """Checks the cross-scenario invariants scenario.schema.json cannot express on its own.

    See "Constraints not enforced by the JSON Schema itself" in
    eval/personas/README.md for the source of these rules.
    """
    report = ValidationReport()

    seen_scenario_ids: set[str] = set()
    roles_covered: set[str] = set()

    for scenario in scenarios:
        scenario_id = scenario.get("scenario_id", "<missing scenario_id>")

        if scenario_id in seen_scenario_ids:
            report.errors.append(f"{scenario_id}: duplicate scenario_id")
        seen_scenario_ids.add(scenario_id)

        role = scenario.get("persona", {}).get("role")
        if role:
            roles_covered.add(role)

        task_ids = {task["task_id"] for task in scenario.get("tasks", [])}

        expected_top_task_id = scenario.get("expected_top_task_id")
        if expected_top_task_id not in task_ids:
            report.errors.append(
                f"{scenario_id}: expected_top_task_id "
                f"{expected_top_task_id!r} is not one of this scenario's task_ids {sorted(task_ids)}"
            )

        for task in scenario.get("tasks", []):
            for dep in task.get("dependencies", []):
                if dep not in task_ids:
                    report.errors.append(
                        f"{scenario_id}: task {task.get('task_id')!r} depends on "
                        f"{dep!r}, which is not a task_id in this scenario"
                    )

        source = scenario.get("source", {})
        if source.get("type") in SOURCE_TYPES_REQUIRING_DETAIL and not source.get("source_detail"):
            report.errors.append(
                f"{scenario_id}: source.type is {source.get('type')!r} but source_detail is empty"
            )

        if scenario.get("mentor_approved") is not True:
            report.warnings.append(f"{scenario_id}: not yet mentor_approved")

    missing_roles = ALL_ROLES - roles_covered
    if missing_roles:
        report.warnings.append(
            f"no scenario yet for role(s): {', '.join(sorted(missing_roles))}"
        )

    return report
