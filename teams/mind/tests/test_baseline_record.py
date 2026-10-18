"""Per-role breakdown (`harness.split_by_role`) and the durable baseline record
(`baseline_record`) for #486."""

import json
import shutil
from collections import Counter
from datetime import date
from pathlib import Path

import pytest
from pydantic import ValidationError

from mind.evaluation.baseline_models import SkipBlockedNearestDeadlineModel
from mind.evaluation.baseline_record import (
    BaselineRecord,
    BaselineRecordError,
    PersonaSetInfo,
    Provenance,
    ScoreSummary,
    build_baseline_record,
    load_baseline_record,
    write_baseline_record,
)
from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import run, split_by_role
from mind.evaluation.hierarchy import NODES_FILENAME
from mind.evaluation.personas import load_personas
from mind.evaluation.personas_checksum import compute_personas_checksum
from mind.evaluation.placeholder_scenarios import PLACEHOLDER_SCENARIOS

REAL_PERSONAS_DIR = Path(__file__).resolve().parents[1] / "eval" / "personas"
REAL_HIERARCHY_DIR = REAL_PERSONAS_DIR.parent / "hierarchy"
ALL_ROLES = {"CEO", "Developer", "Field Engineer", "Higher Manager", "Manager", "Salesperson"}
FAKE_PROVENANCE = Provenance(model_commit="a" * 40, persona_set_commit="b" * 40)


# -- split_by_role -------------------------------------------------------------


def test_split_by_role_covers_every_role_of_the_real_set_and_adds_up():
    scenarios = load_personas(REAL_PERSONAS_DIR)
    # Not DummyModel: it ignores every field, so its per-role numbers say little.
    combined = run(SkipBlockedNearestDeadlineModel(), scenarios)

    roles = split_by_role(scenarios, combined)

    assert set(roles) == ALL_ROLES
    # Per-role counts are read from the set, not hard-coded, so adding a scenario to a
    # role does not break this test.
    assert {role: r.total_scenarios for role, r in roles.items()} == dict(
        Counter(s.persona.role for s in scenarios)
    )
    assert sum(r.total_scenarios for r in roles.values()) == combined.total_scenarios
    assert sum(r.correct_top1 for r in roles.values()) == combined.correct_top1


def test_split_by_role_puts_each_scenario_under_its_own_role():
    scenarios = load_personas(REAL_PERSONAS_DIR)
    role_of = {s.scenario_id: s.persona.role for s in scenarios}

    roles = split_by_role(scenarios, run(DummyModel(), scenarios))

    for role, report in roles.items():
        assert {role_of[r.scenario_id] for r in report.scenario_results} == {role}
        assert report.top1_accuracy == report.correct_top1 / report.total_scenarios


def test_split_by_role_rejects_scenarios_in_a_different_order_than_the_report():
    report = run(DummyModel(), PLACEHOLDER_SCENARIOS)

    with pytest.raises(ValueError, match="out of order"):
        split_by_role(list(reversed(PLACEHOLDER_SCENARIOS)), report)


# -- build_baseline_record -------------------------------------------------------


@pytest.fixture(scope="module")
def placeholder_dir(tmp_path_factory) -> Path:
    """A persona directory that really contains PLACEHOLDER_SCENARIOS, so a record built
    from them has a checksum, a path and a scenario count that all describe the same set."""
    directory = tmp_path_factory.mktemp("placeholder_personas")
    for scenario in PLACEHOLDER_SCENARIOS:
        (directory / f"{scenario.scenario_id}.json").write_text(
            scenario.model_dump_json(indent=2), encoding="utf-8"
        )
    return directory


def _record(personas_dir: Path) -> BaselineRecord:
    combined = run(DummyModel(), PLACEHOLDER_SCENARIOS)
    return build_baseline_record(
        recorded_on=date(2026, 10, 4),
        provenance=FAKE_PROVENANCE,
        personas_path=personas_dir,
        hierarchy_dir=None,
        scenarios=PLACEHOLDER_SCENARIOS,
        combined=combined,
        groups={},
        roles=split_by_role(PLACEHOLDER_SCENARIOS, combined),
    )


def test_record_is_labelled_with_date_model_commits_metric_and_persona_set(placeholder_dir):
    record = _record(placeholder_dir)

    assert record.recorded_on == date(2026, 10, 4)
    assert record.model_name == "DummyModel"
    assert record.model_commit == "a" * 40
    assert record.metric == "top-1 accuracy"
    assert record.format_version == 1
    assert record.persona_set == PersonaSetInfo(
        path=placeholder_dir.as_posix(),
        checksum=compute_personas_checksum(placeholder_dir, hierarchy_dir=None),
        commit="b" * 40,
        scenarios=2,
        team_lead_approved=0,
    )


def _real_record(personas_path: Path, hierarchy_dir: Path) -> BaselineRecord:
    scenarios = load_personas(personas_path)
    combined = run(DummyModel(), scenarios)
    return build_baseline_record(
        recorded_on=date(2026, 10, 4),
        provenance=FAKE_PROVENANCE,
        personas_path=personas_path,
        hierarchy_dir=hierarchy_dir,
        scenarios=scenarios,
        combined=combined,
        groups={},
        roles={},
    )


def test_record_stores_paths_relative_to_the_team_dir_whether_given_absolute_or_relative(
    monkeypatch,
):
    absolute = _real_record(REAL_PERSONAS_DIR, REAL_HIERARCHY_DIR).persona_set

    monkeypatch.chdir(REAL_PERSONAS_DIR.parent.parent)  # teams/mind, as `make eval` runs
    relative = _real_record(Path("eval/personas"), Path("eval/hierarchy")).persona_set

    # A committed record must not carry the recorder's machine layout.
    assert (absolute.path, absolute.hierarchy_path) == ("eval/personas", "eval/hierarchy")
    assert (relative.path, relative.hierarchy_path) == ("eval/personas", "eval/hierarchy")


def test_record_keeps_a_path_outside_the_team_dir_as_given(placeholder_dir):
    # A temporary copy has no team-relative form; it is stored as it was passed.
    assert _record(placeholder_dir).persona_set.path == placeholder_dir.as_posix()


def test_record_carries_overall_per_role_and_per_scenario_results(placeholder_dir):
    record = _record(placeholder_dir)

    # DummyModel ties every task and the harness picks the first; both
    # placeholder scenarios list their correct task first.
    assert record.combined == ScoreSummary(correct_top1=2, total_scenarios=2, top1_accuracy=1.0)
    assert set(record.roles) == {"Developer", "Manager"}
    assert [r.scenario_id for r in record.scenario_results] == [
        "developer_placeholder_1",
        "manager_placeholder_1",
    ]
    json.loads(record.model_dump_json())  # everything in it is JSON-serializable


def test_build_refuses_a_report_whose_accuracy_does_not_match_its_counts(placeholder_dir):
    combined = run(DummyModel(), PLACEHOLDER_SCENARIOS)
    wrong = combined.model_copy(update={"top1_accuracy": 0.5})  # counts say 2/2 = 1.0

    with pytest.raises(ValidationError, match="top1_accuracy is 0.5"):
        build_baseline_record(
            recorded_on=date(2026, 10, 4),
            provenance=FAKE_PROVENANCE,
            personas_path=placeholder_dir,
            hierarchy_dir=None,
            scenarios=PLACEHOLDER_SCENARIOS,
            combined=wrong,
            groups={},
            roles={},
        )


# -- write_baseline_record -------------------------------------------------------


def test_write_creates_the_file_and_its_directory(tmp_path, placeholder_dir):
    path = tmp_path / "baselines" / "baseline_score_2026-10-04.json"

    write_baseline_record(path, _record(placeholder_dir))

    assert json.loads(path.read_text(encoding="utf-8"))["model_commit"] == "a" * 40


def test_write_never_overwrites_an_existing_record(tmp_path, placeholder_dir):
    path = tmp_path / "baseline_score.json"
    write_baseline_record(path, _record(placeholder_dir))
    original = path.read_text(encoding="utf-8")

    with pytest.raises(BaselineRecordError, match="never overwritten"):
        write_baseline_record(
            path, _record(placeholder_dir).model_copy(update={"model_name": "Other"})
        )

    assert path.read_text(encoding="utf-8") == original


# -- Provenance ----------------------------------------------------------------


@pytest.mark.parametrize("sha", ["c7e6c507", "c7e6c50786d44e0e9b1f2a3c4d5e6f7a8b9c0d1e"])
def test_provenance_accepts_short_and_full_commit_shas(sha):
    assert Provenance(model_commit=sha, persona_set_commit=sha).model_commit == sha


@pytest.mark.parametrize(
    "bad",
    [
        "",  # forgotten
        "c7e6c5",  # too short to identify a commit
        "C7E6C507",  # git prints lowercase
        "main",  # a branch name moves; only a sha pins what was scored
        "c7e6c507 ",  # stray whitespace from a paste
    ],
)
def test_provenance_rejects_anything_that_is_not_a_commit_sha(bad):
    with pytest.raises(BaselineRecordError, match="is not a git commit sha"):
        Provenance(model_commit=bad, persona_set_commit="b" * 40)
    with pytest.raises(BaselineRecordError, match="is not a git commit sha"):
        Provenance(model_commit="a" * 40, persona_set_commit=bad)


# -- load_baseline_record --------------------------------------------------------


def _write_real_record(path: Path):
    """Record SkipBlockedNearestDeadlineModel on the real set (non-trivial scores,
    passes and misses both present) and return the Report it was built from."""
    scenarios = load_personas(REAL_PERSONAS_DIR)
    combined = run(SkipBlockedNearestDeadlineModel(), scenarios)
    write_baseline_record(
        path,
        build_baseline_record(
            recorded_on=date(2026, 10, 4),
            provenance=FAKE_PROVENANCE,
            personas_path=REAL_PERSONAS_DIR,
            hierarchy_dir=REAL_HIERARCHY_DIR,
            scenarios=scenarios,
            combined=combined,
            groups={},
            roles=split_by_role(scenarios, combined),
        ),
    )
    return combined


def _load_real(path: Path):
    """Load a record against the real persona set and hierarchy."""
    return load_baseline_record(
        path, personas_path=REAL_PERSONAS_DIR, hierarchy_dir=REAL_HIERARCHY_DIR
    )


def _copy_real_set(tmp_path: Path, name: str) -> Path:
    """Copy the real persona set *and* the hierarchy fixture next to it, laid out as
    in the repo (`<name>/personas`, `<name>/hierarchy`); return the personas path.
    A recorded baseline's checksum covers both, so a copy without the hierarchy is a
    different set."""
    root = tmp_path / name
    shutil.copytree(REAL_PERSONAS_DIR, root / "personas")
    shutil.copytree(REAL_HIERARCHY_DIR, root / "hierarchy")
    return root / "personas"


def _tamper(path: Path, edit) -> None:
    record = json.loads(path.read_text(encoding="utf-8"))
    edit(record)
    path.write_text(json.dumps(record), encoding="utf-8")


def test_load_gives_back_exactly_the_report_that_was_recorded(tmp_path):
    path = tmp_path / "baseline_score.json"
    original = _write_real_record(path)

    assert _load_real(path) == original


def test_load_rejects_a_record_whose_totals_do_not_match_its_scenarios(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    def one_more_correct(record):
        c = record["combined"]
        c["correct_top1"] += 1
        c["top1_accuracy"] = c["correct_top1"] / c["total_scenarios"]  # accuracy still agrees

    _tamper(path, one_more_correct)

    with pytest.raises(BaselineRecordError, match="correct_top1"):
        _load_real(path)


def test_load_rejects_a_truncated_record(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    _tamper(path, lambda r: r["scenario_results"].pop())

    with pytest.raises(BaselineRecordError, match="total_scenarios"):
        _load_real(path)


def test_load_rejects_a_scenario_whose_passed_flag_contradicts_its_prediction(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)

    def flip_first_miss(record):
        miss = next(r for r in record["scenario_results"] if not r["passed"])
        miss["passed"] = True
        c = record["combined"]
        c["correct_top1"] += 1  # totals still add up
        c["top1_accuracy"] = c["correct_top1"] / c["total_scenarios"]

    _tamper(path, flip_first_miss)

    with pytest.raises(BaselineRecordError, match="marked passed=True"):
        _load_real(path)


def test_load_rejects_a_file_that_is_not_a_baseline_record(tmp_path):
    path = tmp_path / "baseline_score.json"
    path.write_text(json.dumps({"combined": {}}), encoding="utf-8")

    with pytest.raises(BaselineRecordError, match="not a valid baseline record"):
        _load_real(path)


def test_load_rejects_an_accuracy_that_does_not_match_its_own_counts(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    # Only the accuracy is edited (20/30 = 0.667 -> 0.95); counts and scenarios untouched.
    _tamper(path, lambda r: r["combined"].update(top1_accuracy=0.95))

    with pytest.raises(BaselineRecordError, match="top1_accuracy is 0.95"):
        _load_real(path)


def test_load_error_names_the_field_at_fault(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    _tamper(path, lambda r: r.update(model_commit="main"))

    with pytest.raises(BaselineRecordError, match="model_commit"):
        _load_real(path)


# -- the persona-set checksum ----------------------------------------------------


def test_the_checksum_is_computed_from_the_files_not_given_by_the_caller(placeholder_dir):
    record = _record(placeholder_dir)

    # build_baseline_record has no checksum parameter; the value can only come from the set.
    assert record.persona_set.checksum == compute_personas_checksum(
        placeholder_dir, hierarchy_dir=None
    )
    assert len(record.persona_set.checksum) == 64


def test_load_accepts_an_identical_copy_of_the_persona_set_at_another_path(tmp_path):
    path = tmp_path / "baseline_score.json"
    original = _write_real_record(path)
    copy = _copy_real_set(tmp_path, "copy")

    # The checksum is about the content, so where the set lives does not matter.
    assert (
        load_baseline_record(path, personas_path=copy, hierarchy_dir=copy.parent / "hierarchy")
        == original
    )


def test_load_refuses_when_a_scenario_was_edited_after_the_baseline_was_recorded(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    changed = _copy_real_set(tmp_path, "changed")
    victim = min(changed.rglob("developer_*.json"))
    data = json.loads(victim.read_text(encoding="utf-8"))
    data["expected_reason"] += " (edited after the baseline was recorded)"
    victim.write_text(json.dumps(data), encoding="utf-8")
    assert compute_personas_checksum(
        changed, hierarchy_dir=changed.parent / "hierarchy"
    ) != compute_personas_checksum(REAL_PERSONAS_DIR, hierarchy_dir=REAL_HIERARCHY_DIR)

    with pytest.raises(BaselineRecordError, match="same fixed set"):
        load_baseline_record(
            path, personas_path=changed, hierarchy_dir=changed.parent / "hierarchy"
        )


def test_load_refuses_when_a_scenario_was_added_after_the_baseline_was_recorded(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    grown = _copy_real_set(tmp_path, "grown")
    extra = next(grown.rglob("developer_*.json"))
    (extra.parent / "developer_extra.json").write_text(extra.read_text(encoding="utf-8"), encoding="utf-8")

    with pytest.raises(BaselineRecordError, match="same fixed set"):
        load_baseline_record(
            path, personas_path=grown, hierarchy_dir=grown.parent / "hierarchy"
        )


def test_load_refuses_when_a_hierarchy_node_was_edited_after_the_baseline_was_recorded(
    tmp_path,
):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    changed = _copy_real_set(tmp_path, "node_changed")
    nodes_file = changed.parent / "hierarchy" / NODES_FILENAME
    data = json.loads(nodes_file.read_text(encoding="utf-8"))
    original_importance = data["nodes"][0]["importance"]
    data["nodes"][0]["importance"] = 0.35  # not one of the levels the set uses
    assert data["nodes"][0]["importance"] != original_importance
    nodes_file.write_text(json.dumps(data), encoding="utf-8")

    # Every scenario file is untouched; only what their parents point at changed.
    with pytest.raises(BaselineRecordError, match="same fixed set"):
        load_baseline_record(
            path, personas_path=changed, hierarchy_dir=changed.parent / "hierarchy"
        )


def test_load_names_the_hierarchy_path_mismatch_when_its_location_also_changed(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)  # recorded hierarchy_path is "eval/hierarchy"
    moved = _copy_real_set(tmp_path, "moved")
    nodes_file = moved.parent / "hierarchy" / NODES_FILENAME
    data = json.loads(nodes_file.read_text(encoding="utf-8"))
    data["nodes"][0]["importance"] = 0.35  # forces a checksum mismatch
    nodes_file.write_text(json.dumps(data), encoding="utf-8")

    with pytest.raises(BaselineRecordError, match=r"hierarchy path: recorded 'eval/hierarchy', now '"):
        load_baseline_record(path, personas_path=moved, hierarchy_dir=moved.parent / "hierarchy")


def test_load_does_not_mention_a_hierarchy_mismatch_when_only_its_content_changed(tmp_path):
    # Record and load use the exact same hierarchy_dir value, so only its content,
    # never its location, can differ between the two.
    copy = _copy_real_set(tmp_path, "same_location")
    hierarchy_dir = copy.parent / "hierarchy"
    scenarios = load_personas(copy)
    combined = run(SkipBlockedNearestDeadlineModel(), scenarios)
    path = tmp_path / "baseline_score.json"
    write_baseline_record(
        path,
        build_baseline_record(
            recorded_on=date(2026, 10, 4),
            provenance=FAKE_PROVENANCE,
            personas_path=copy,
            hierarchy_dir=hierarchy_dir,
            scenarios=scenarios,
            combined=combined,
            groups={},
            roles=split_by_role(scenarios, combined),
        ),
    )
    nodes_file = hierarchy_dir / NODES_FILENAME
    data = json.loads(nodes_file.read_text(encoding="utf-8"))
    data["nodes"][0]["importance"] = 0.35  # forces a checksum mismatch, same file in place
    nodes_file.write_text(json.dumps(data), encoding="utf-8")

    with pytest.raises(BaselineRecordError, match="same fixed set") as exc_info:
        load_baseline_record(path, personas_path=copy, hierarchy_dir=hierarchy_dir)

    assert "hierarchy path" not in str(exc_info.value)


def test_load_refuses_when_the_persona_directory_is_missing(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)

    with pytest.raises(BaselineRecordError, match="cannot checksum"):
        load_baseline_record(
            path, personas_path=tmp_path / "does_not_exist", hierarchy_dir=REAL_HIERARCHY_DIR
        )


def test_load_rejects_a_checksum_that_is_not_a_sha256_digest(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    _tamper(path, lambda r: r["persona_set"].update(checksum="abc123"))

    with pytest.raises(BaselineRecordError, match="checksum"):
        _load_real(path)


# -- format_version and the breakdown checks -------------------------------------


def test_a_record_carries_its_format_version(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)

    assert json.loads(path.read_text(encoding="utf-8"))["format_version"] == 1


def test_a_record_from_another_format_version_says_so_instead_of_a_validation_wall(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    _tamper(path, lambda r: r.update(format_version=2))

    with pytest.raises(BaselineRecordError, match="format version 2.*reads version 1"):
        _load_real(path)


def test_a_record_with_no_format_version_is_read_as_version_1(tmp_path):
    path = tmp_path / "baseline_score.json"
    original = _write_real_record(path)
    _tamper(path, lambda r: r.pop("format_version"))

    assert _load_real(path) == original


def test_load_rejects_roles_that_do_not_add_up_to_the_combined_numbers(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)

    def inflate_one_role(record):
        role = next(iter(record["roles"].values()))
        role["total_scenarios"] += 1
        role["top1_accuracy"] = role["correct_top1"] / role["total_scenarios"]

    _tamper(path, inflate_one_role)

    with pytest.raises(BaselineRecordError, match="roles add up to"):
        _load_real(path)


def test_split_by_role_gets_its_accuracy_from_the_metrics_module(monkeypatch):
    scenarios = load_personas(REAL_PERSONAS_DIR)
    combined = run(DummyModel(), scenarios)
    monkeypatch.setattr("mind.evaluation.harness.top1_accuracy", lambda predictions, scenarios: 0.123)

    roles = split_by_role(scenarios, combined)

    assert {r.top1_accuracy for r in roles.values()} == {0.123}
