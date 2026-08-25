import json
import shutil
from pathlib import Path

import pytest

from mind.evaluation.personas import PersonaSchemaError, load_personas, validate_personas

REAL_SCHEMA_PATH = (
    Path(__file__).resolve().parents[1] / "eval" / "personas" / "scenario.schema.json"
)


def make_scenario(
    scenario_id: str = "developer_junior_late_pr_review",
    role: str = "Developer",
    expected_top_task_id: str = "task_B",
    mentor_approved: bool = True,
    source_type: str = "synthetic",
    source_detail: str | None = None,
) -> dict:
    return {
        "scenario_id": scenario_id,
        "persona": {"role": role},
        "context": {
            "free_time_minutes": 30,
            "current_datetime": "2026-08-23T14:00:00",
            "time_horizon": "today",
        },
        "tasks": [
            {
                "task_id": "task_A",
                "title": "Write architecture doc",
                "description": "Long-form design doc, not urgent",
                "estimated_duration_minutes": 120,
                "tier": "Strategic",
                "blocked": False,
            },
            {
                "task_id": "task_B",
                "title": "Review PR #482",
                "description": "Code review for the auth refactor PR, deadline today",
                "deadline": "2026-08-23T17:00:00",
                "estimated_duration_minutes": 20,
                "tier": "Operational",
                "blocked": False,
            },
        ],
        "expected_top_task_id": expected_top_task_id,
        "expected_reason": "Task B fits the 30-minute window and has a same-day deadline.",
        "source": {"type": source_type, "source_detail": source_detail},
        "mentor_approved": mentor_approved,
    }


def write_personas_dir(tmp_path: Path, scenarios: list[dict]) -> Path:
    personas_dir = tmp_path / "personas"
    personas_dir.mkdir()
    shutil.copy(REAL_SCHEMA_PATH, personas_dir / "scenario.schema.json")
    for scenario in scenarios:
        (personas_dir / f"{scenario['scenario_id']}.json").write_text(
            json.dumps(scenario), encoding="utf-8"
        )
    return personas_dir


# -- load_personas ------------------------------------------------------------


def test_load_personas_valid_scenario(tmp_path):
    scenario = make_scenario()
    personas_dir = write_personas_dir(tmp_path, [scenario])
    assert load_personas(personas_dir) == [scenario]


def test_load_personas_missing_directory_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_personas(tmp_path / "does_not_exist")


def test_load_personas_empty_directory_returns_empty_list(tmp_path):
    personas_dir = write_personas_dir(tmp_path, [])
    assert load_personas(personas_dir) == []


def test_load_personas_schema_invalid_scenario_raises(tmp_path):
    scenario = make_scenario()
    del scenario["mentor_approved"]  # required field missing -> schema violation

    personas_dir = write_personas_dir(tmp_path, [scenario])
    with pytest.raises(PersonaSchemaError, match="mentor_approved"):
        load_personas(personas_dir)


# -- validate_personas ---------------------------------------------------------


def test_validate_personas_well_formed_set_has_no_errors():
    scenarios = [make_scenario()]
    report = validate_personas(scenarios)
    assert report.ok
    assert report.errors == []


def test_validate_personas_duplicate_scenario_id_is_error():
    scenarios = [make_scenario(), make_scenario()]
    report = validate_personas(scenarios)
    assert not report.ok
    assert any("duplicate scenario_id" in e for e in report.errors)


def test_validate_personas_dangling_expected_top_task_id_is_error():
    scenarios = [make_scenario(expected_top_task_id="task_does_not_exist")]
    report = validate_personas(scenarios)
    assert not report.ok
    assert any("expected_top_task_id" in e for e in report.errors)


def test_validate_personas_missing_source_detail_is_error():
    scenarios = [make_scenario(source_type="AMI", source_detail=None)]
    report = validate_personas(scenarios)
    assert not report.ok
    assert any("source_detail" in e for e in report.errors)


def test_validate_personas_missing_role_is_warning_not_error():
    scenarios = [make_scenario(role="Developer")]
    report = validate_personas(scenarios)
    assert report.ok
    assert any("CEO" in w for w in report.warnings)


def test_validate_personas_unapproved_scenario_is_warning_not_error():
    scenarios = [make_scenario(mentor_approved=False)]
    report = validate_personas(scenarios)
    assert report.ok
    assert any("mentor_approved" in w for w in report.warnings)
