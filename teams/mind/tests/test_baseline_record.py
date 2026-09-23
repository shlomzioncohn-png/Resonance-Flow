"""Per-role breakdown (`harness.split_by_role`) and the durable baseline record
(`baseline_record`) for #486."""

import json
from datetime import date
from pathlib import Path

import pytest

from mind.evaluation.baseline_models import SkipBlockedNearestDeadlineModel
from mind.evaluation.baseline_record import (
    BaselineRecordError,
    Provenance,
    build_baseline_record,
    load_baseline_record,
    write_baseline_record,
)
from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import run, split_by_role
from mind.evaluation.personas import load_personas
from mind.evaluation.placeholder_scenarios import PLACEHOLDER_SCENARIOS

REAL_PERSONAS_DIR = Path(__file__).resolve().parents[1] / "eval" / "personas"
ALL_ROLES = {"CEO", "Developer", "Field Engineer", "Higher Manager", "Manager", "Salesperson"}
FAKE_PROVENANCE = Provenance(model_commit="a" * 40, persona_set_commit="b" * 40)


# -- split_by_role -------------------------------------------------------------


def test_split_by_role_covers_every_role_of_the_real_set_and_adds_up():
    scenarios = load_personas(REAL_PERSONAS_DIR)
    # Not DummyModel: it ignores every field, so its per-role numbers say little.
    combined = run(SkipBlockedNearestDeadlineModel(), scenarios)

    roles = split_by_role(scenarios, combined)

    assert set(roles) == ALL_ROLES
    assert {role: r.total_scenarios for role, r in roles.items()} == dict.fromkeys(ALL_ROLES, 5)
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


def _record() -> dict:
    combined = run(DummyModel(), PLACEHOLDER_SCENARIOS)
    return build_baseline_record(
        recorded_on=date(2026, 10, 4),
        provenance=FAKE_PROVENANCE,
        personas_path=Path("eval/personas"),
        scenarios=PLACEHOLDER_SCENARIOS,
        combined=combined,
        groups={},
        roles=split_by_role(PLACEHOLDER_SCENARIOS, combined),
    )


def test_record_is_labelled_with_date_model_commits_metric_and_persona_set():
    record = _record()

    assert record["recorded_on"] == "2026-10-04"
    assert record["model_name"] == "DummyModel"
    assert record["model_commit"] == "a" * 40
    assert record["metric"] == "top-1 accuracy"
    assert record["persona_set"] == {
        "path": "eval/personas",
        "commit": "b" * 40,
        "scenarios": 2,
        "team_lead_approved": 0,
        "mentor_approved": 0,
    }


def test_record_carries_overall_per_role_and_per_scenario_results():
    record = _record()

    # DummyModel ties every task and the harness picks the first; both
    # placeholder scenarios list their correct task first.
    assert record["combined"] == {"correct_top1": 2, "total_scenarios": 2, "top1_accuracy": 1.0}
    assert set(record["roles"]) == {"Developer", "Manager"}
    assert [r["scenario_id"] for r in record["scenario_results"]] == [
        "developer_placeholder_1",
        "manager_placeholder_1",
    ]
    json.dumps(record)  # everything in it is JSON-serializable


# -- write_baseline_record -------------------------------------------------------


def test_write_creates_the_file_and_its_directory(tmp_path):
    path = tmp_path / "baselines" / "baseline_score_2026-10-04.json"

    write_baseline_record(path, _record())

    assert json.loads(path.read_text(encoding="utf-8"))["model_commit"] == "a" * 40


def test_write_never_overwrites_an_existing_record(tmp_path):
    path = tmp_path / "baseline_score.json"
    write_baseline_record(path, _record())
    original = path.read_text(encoding="utf-8")

    with pytest.raises(BaselineRecordError, match="never overwritten"):
        write_baseline_record(path, {"something": "else"})

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
            personas_path=Path("eval/personas"),
            scenarios=scenarios,
            combined=combined,
            groups={},
            roles=split_by_role(scenarios, combined),
        ),
    )
    return combined


def _tamper(path: Path, edit) -> None:
    record = json.loads(path.read_text(encoding="utf-8"))
    edit(record)
    path.write_text(json.dumps(record), encoding="utf-8")


def test_load_gives_back_exactly_the_report_that_was_recorded(tmp_path):
    path = tmp_path / "baseline_score.json"
    original = _write_real_record(path)

    assert load_baseline_record(path) == original


def test_load_rejects_a_record_whose_totals_do_not_match_its_scenarios(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    _tamper(path, lambda r: r["combined"].update(correct_top1=r["combined"]["correct_top1"] + 1))

    with pytest.raises(BaselineRecordError, match="correct_top1"):
        load_baseline_record(path)


def test_load_rejects_a_truncated_record(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)
    _tamper(path, lambda r: r["scenario_results"].pop())

    with pytest.raises(BaselineRecordError, match="total_scenarios"):
        load_baseline_record(path)


def test_load_rejects_a_scenario_whose_passed_flag_contradicts_its_prediction(tmp_path):
    path = tmp_path / "baseline_score.json"
    _write_real_record(path)

    def flip_first_miss(record):
        miss = next(r for r in record["scenario_results"] if not r["passed"])
        miss["passed"] = True
        record["combined"]["correct_top1"] += 1  # totals still add up

    _tamper(path, flip_first_miss)

    with pytest.raises(BaselineRecordError, match="marked passed=True"):
        load_baseline_record(path)


def test_load_rejects_a_file_that_is_not_a_baseline_record(tmp_path):
    path = tmp_path / "baseline_score.json"
    path.write_text(json.dumps({"combined": {}}), encoding="utf-8")

    with pytest.raises(BaselineRecordError, match="not a readable baseline record"):
        load_baseline_record(path)
