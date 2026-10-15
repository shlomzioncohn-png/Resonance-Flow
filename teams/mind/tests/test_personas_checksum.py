"""eval/personas/personas.sha256 pins the content of every approved scenario file.

If this fails after an INTENTIONAL, reviewed change to the scenario set
(new scenario, or a team-lead-approved edit), regenerate and commit:

    python -m mind.evaluation.personas_checksum

If it fails and you did NOT expect eval/personas/ to have changed, stop —
something in the set drifted after sign-off without anyone noticing.
"""

import pytest

from mind.evaluation.hierarchy import HIERARCHY_PATH
from mind.evaluation.personas_checksum import (
    CHECKSUM_PATH,
    HIERARCHY_DIR,
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

    before = compute_personas_checksum(tmp_path, hierarchy_dir=None)
    original.write_text('{"tier": "tactical"}', encoding="utf-8")
    after = compute_personas_checksum(tmp_path, hierarchy_dir=None)

    assert before != after


def test_checksum_ignores_the_generated_schema_file(tmp_path):
    scenario_file = tmp_path / "same_tier"
    scenario_file.mkdir()
    (scenario_file / "example.json").write_text('{"tier": "operational"}', encoding="utf-8")

    before = compute_personas_checksum(tmp_path, hierarchy_dir=None)
    (tmp_path / "scenario.schema.json").write_text('{"anything": true}', encoding="utf-8")
    after = compute_personas_checksum(tmp_path, hierarchy_dir=None)

    assert before == after


def _two_scenario_dir(tmp_path):
    group = tmp_path / "same_tier"
    group.mkdir()
    (group / "first.json").write_text('{"tier": "operational"}', encoding="utf-8")
    (group / "second.json").write_text('{"tier": "tactical"}', encoding="utf-8")
    return group


def test_checksum_changes_if_a_scenario_file_is_renamed_or_moved(tmp_path):
    group = _two_scenario_dir(tmp_path)
    before = compute_personas_checksum(tmp_path, hierarchy_dir=None)

    (group / "first.json").rename(group / "renamed.json")  # same content, new path
    after = compute_personas_checksum(tmp_path, hierarchy_dir=None)

    assert before != after


def test_checksum_changes_if_a_scenario_file_is_deleted(tmp_path):
    group = _two_scenario_dir(tmp_path)
    before = compute_personas_checksum(tmp_path, hierarchy_dir=None)

    (group / "second.json").unlink()
    after = compute_personas_checksum(tmp_path, hierarchy_dir=None)

    assert before != after


def test_personas_dir_points_at_the_real_eval_personas_directory():
    assert PERSONAS_DIR.is_dir()
    assert (PERSONAS_DIR / "same_tier").is_dir()
    assert (PERSONAS_DIR / "cross_tier").is_dir()


def _personas_and_hierarchy(tmp_path):
    personas = tmp_path / "personas"
    personas.mkdir()
    _two_scenario_dir(personas)
    hierarchy = tmp_path / "hierarchy"
    hierarchy.mkdir()
    (hierarchy / "nodes.json").write_text('{"importance": 0.8}', encoding="utf-8")
    return personas, hierarchy


def test_checksum_changes_if_a_hierarchy_node_changes(tmp_path):
    personas, hierarchy = _personas_and_hierarchy(tmp_path)
    before = compute_personas_checksum(personas, hierarchy_dir=hierarchy)

    (hierarchy / "nodes.json").write_text('{"importance": 0.2}', encoding="utf-8")
    after = compute_personas_checksum(personas, hierarchy_dir=hierarchy)

    assert before != after


def test_checksum_covers_the_hierarchy_only_when_asked(tmp_path):
    personas, hierarchy = _personas_and_hierarchy(tmp_path)

    without = compute_personas_checksum(personas, hierarchy_dir=None)

    assert without != compute_personas_checksum(personas, hierarchy_dir=hierarchy)


def test_hierarchy_dir_must_be_stated_explicitly(tmp_path):
    # No default: a caller who forgets it must fail, not silently get a checksum
    # that can never match personas.sha256.
    personas, _ = _personas_and_hierarchy(tmp_path)

    with pytest.raises(TypeError, match="hierarchy_dir"):
        compute_personas_checksum(personas)  # type: ignore[call-arg]


def test_hierarchy_dir_points_at_the_real_eval_hierarchy_directory():
    assert HIERARCHY_DIR.is_dir()
    assert HIERARCHY_PATH.is_file()
    assert HIERARCHY_PATH.parent == HIERARCHY_DIR
    assert HIERARCHY_DIR.parent == PERSONAS_DIR.parent  # a sibling, not inside personas/
