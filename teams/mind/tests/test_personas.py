import json
import shutil
from pathlib import Path

import pytest

from mind.evaluation.interfaces import PersonaEvaluationScenario
from mind.evaluation.personas import PersonaSchemaError, load_personas, validate_personas

REAL_SCHEMA_PATH = (
    Path(__file__).resolve().parents[1] / "eval" / "personas" / "scenario.schema.json"
)


def make_scenario(
    scenario_id: str = "developer_late_pr_review",
    role: str = "Developer",
    expected_top_task_id: str = "task_b",
    team_lead_approved: bool = True,
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
                "task_id": "task_a",
                "title": "Write architecture doc",
                "description": "Long-form design doc, not urgent",
                "effort_estimate": 120,
                "tier": "strategic",
                "blocked": False,
                "parent_task_id": None,
            },
            {
                "task_id": "task_b",
                "title": "Review PR #482",
                "description": "Code review for the auth refactor PR, deadline today",
                "deadline": "2026-08-23T17:00:00",
                "effort_estimate": 20,
                "tier": "operational",
                "blocked": False,
                "parent_task_id": None,
            },
        ],
        "expected_top_task_id": expected_top_task_id,
        "expected_reason": "Task b has a same-day deadline and Task a has none, so Task b is the pick.",
        "source": {"type": source_type, "source_detail": source_detail},
        "team_lead_approved": team_lead_approved,
        "mentor_approved": mentor_approved,
    }


def make_scenario_model(**kwargs) -> PersonaEvaluationScenario:
    return PersonaEvaluationScenario.model_validate(make_scenario(**kwargs))


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

    loaded = load_personas(personas_dir)

    assert len(loaded) == 1
    assert isinstance(loaded[0], PersonaEvaluationScenario)
    assert loaded[0].scenario_id == scenario["scenario_id"]
    assert loaded[0].expected_top_task_id == "task_b"
    assert [t.task_id for t in loaded[0].tasks] == ["task_a", "task_b"]


def test_load_personas_missing_directory_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_personas(tmp_path / "does_not_exist")


def test_load_personas_empty_directory_returns_empty_list(tmp_path):
    personas_dir = write_personas_dir(tmp_path, [])
    assert load_personas(personas_dir) == []


def test_load_personas_recurses_into_group_subdirectories(tmp_path):
    # The real set is split into same_tier/ and cross_tier/ subdirectories;
    # loading the parent must pick up both, loading one subdir must pick up just it.
    personas_dir = write_personas_dir(tmp_path, [])
    same_tier = personas_dir / "same_tier"
    cross_tier = personas_dir / "cross_tier"
    same_tier.mkdir()
    cross_tier.mkdir()
    (same_tier / "a.json").write_text(
        json.dumps(make_scenario(scenario_id="developer_same_tier_a")), encoding="utf-8"
    )
    (cross_tier / "b.json").write_text(
        json.dumps(make_scenario(scenario_id="developer_cross_tier_b")), encoding="utf-8"
    )

    assert {s.scenario_id for s in load_personas(personas_dir)} == {
        "developer_same_tier_a",
        "developer_cross_tier_b",
    }
    assert [s.scenario_id for s in load_personas(same_tier)] == ["developer_same_tier_a"]


def test_load_personas_schema_invalid_scenario_raises(tmp_path):
    scenario = make_scenario()
    del scenario["mentor_approved"]  # required field missing -> schema violation

    personas_dir = write_personas_dir(tmp_path, [scenario])
    with pytest.raises(PersonaSchemaError, match="mentor_approved"):
        load_personas(personas_dir)


def test_load_personas_dangling_expected_top_task_id_raises(tmp_path):
    # Now an intra-scenario invariant enforced by the model, so it fails at load.
    scenario = make_scenario(expected_top_task_id="task_does_not_exist")
    personas_dir = write_personas_dir(tmp_path, [scenario])
    with pytest.raises(PersonaSchemaError, match="expected_top_task_id"):
        load_personas(personas_dir)


def test_load_personas_missing_source_detail_raises(tmp_path):
    scenario = make_scenario(source_type="AMI", source_detail=None)
    personas_dir = write_personas_dir(tmp_path, [scenario])
    with pytest.raises(PersonaSchemaError, match="source_detail"):
        load_personas(personas_dir)


def test_load_personas_reports_every_offending_file(tmp_path):
    good = make_scenario(scenario_id="developer_good")
    bad = make_scenario(scenario_id="developer_bad", expected_top_task_id="nope")
    personas_dir = write_personas_dir(tmp_path, [good, bad])
    with pytest.raises(PersonaSchemaError, match="developer_bad"):
        load_personas(personas_dir)


# -- validate_personas ------------------------------------------------------


def test_validate_personas_well_formed_set_has_no_errors():
    report = validate_personas([make_scenario_model()])
    assert report.ok
    assert report.errors == []


def test_validate_personas_empty_set_is_error_not_warning():
    # "measured nothing" must not pass the way "measured everything" does.
    report = validate_personas([])
    assert not report.ok
    assert any("no scenarios loaded" in e for e in report.errors)


def test_validate_personas_duplicate_scenario_id_is_error():
    report = validate_personas([make_scenario_model(), make_scenario_model()])
    assert not report.ok
    assert any("duplicate scenario_id" in e for e in report.errors)


def test_validate_personas_missing_role_is_warning_not_error():
    report = validate_personas([make_scenario_model(role="Developer")])
    assert report.ok
    assert any("CEO" in w for w in report.warnings)


def test_validate_personas_unapproved_scenario_is_warning_not_error():
    report = validate_personas([make_scenario_model(mentor_approved=False)])
    assert report.ok
    assert any("mentor_approved" in w for w in report.warnings)


def test_validate_personas_role_count_out_of_range_is_warning_not_error():
    # Only 2 Developer scenarios: below MIN_SCENARIOS_PER_ROLE (4).
    scenarios = [
        make_scenario_model(scenario_id="developer_scenario_1", role="Developer"),
        make_scenario_model(scenario_id="developer_scenario_2", role="Developer"),
    ]
    report = validate_personas(scenarios)
    assert report.ok
    assert any("Developer" in w and "2" in w for w in report.warnings)


def test_validate_personas_total_count_out_of_range_is_warning_not_error():
    report = validate_personas([make_scenario_model()])  # 1 total, below MIN (24)
    assert report.ok
    assert any("total" in w and "24-36" in w for w in report.warnings)
