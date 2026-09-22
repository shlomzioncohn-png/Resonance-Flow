"""eval/personas/personas.sha256 pins the content of every approved scenario file.

If this fails after an INTENTIONAL, reviewed change to the scenario set
(new scenario, or a team-lead-approved edit), regenerate and commit:

    python -m mind.evaluation.personas_checksum

If it fails and you did NOT expect eval/personas/ to have changed, stop —
something in the set drifted after sign-off without anyone noticing.
"""

from mind.evaluation.personas_checksum import (
    CHECKSUM_PATH,
    PERSONAS_DIR,
    compute_personas_checksum,
    render_checksum,
)


def test_recorded_checksum_matches_eval_personas():
    recorded = CHECKSUM_PATH.read_text(encoding="utf-8")

    assert recorded == render_checksum(), (
        "eval/personas/ content no longer matches the recorded checksum in "
        f"{CHECKSUM_PATH} — see this file's module docstring."
    )


def test_checksum_changes_if_a_scenario_field_changes(tmp_path):
    scenario_file = tmp_path / "same_tier"
    scenario_file.mkdir()
    original = scenario_file / "example.json"
    original.write_text('{"tier": "operational"}', encoding="utf-8")

    before = compute_personas_checksum(tmp_path)
    original.write_text('{"tier": "tactical"}', encoding="utf-8")
    after = compute_personas_checksum(tmp_path)

    assert before != after


def test_checksum_ignores_the_generated_schema_file(tmp_path):
    scenario_file = tmp_path / "same_tier"
    scenario_file.mkdir()
    (scenario_file / "example.json").write_text('{"tier": "operational"}', encoding="utf-8")

    before = compute_personas_checksum(tmp_path)
    (tmp_path / "scenario.schema.json").write_text('{"anything": true}', encoding="utf-8")
    after = compute_personas_checksum(tmp_path)

    assert before == after


def test_personas_dir_points_at_the_real_eval_personas_directory():
    assert PERSONAS_DIR.is_dir()
    assert (PERSONAS_DIR / "same_tier").is_dir()
    assert (PERSONAS_DIR / "cross_tier").is_dir()
