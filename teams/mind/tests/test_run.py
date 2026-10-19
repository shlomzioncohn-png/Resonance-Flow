import json
import sys
from pathlib import Path

import pytest

from mind.evaluation.baseline_record import BASELINE_EXISTS_MESSAGE, load_baseline_record
from mind.evaluation.harness import CandidateScore, EvaluationOutput, Report, ScenarioResult
from mind.evaluation.hierarchy import NODES_FILENAME
from mind.evaluation.interfaces import ScoreFactors
from mind.evaluation.run import _report_json, main

REAL_PERSONAS_DIR = Path(__file__).resolve().parents[1] / "eval" / "personas"
REAL_HIERARCHY_DIR = REAL_PERSONAS_DIR.parent / "hierarchy"


def _output() -> EvaluationOutput:
    report = Report(
        model_name="SomeModel",
        total_scenarios=1,
        correct_top1=1,
        top1_accuracy=1.0,
        scenario_results=[
            ScenarioResult(
                scenario_id="some_scenario",
                expected_top_task_id="task_a",
                predicted_top_task_id="task_a",
                passed=True,
                candidates=[
                    CandidateScore(
                        task_id="task_a",
                        score=0.5,
                        factors=ScoreFactors(contributions={"impact": -0.25}),
                    )
                ],
            )
        ],
    )
    return EvaluationOutput(model_name="SomeModel", combined=report, groups={"g": report})


def test_report_json_round_trips_the_output():
    output = _output()
    assert EvaluationOutput.model_validate_json(_report_json(output)) == output


@pytest.mark.parametrize("bad", [float("nan"), float("inf"), float("-inf")])
def test_report_json_refuses_non_finite_values(bad):
    # The bypass from the PR #497 review: a dict mutated after validation. The
    # models can't see it, so the write itself must fail, not emit invalid JSON.
    output = _output()
    factors = output.combined.scenario_results[0].candidates[0].factors
    assert factors is not None
    factors.contributions["impact"] = bad

    with pytest.raises(ValueError):
        _report_json(output)


# -- --record-baseline: what a run leaves behind ---------------------------------


def _run_cli(monkeypatch, tmp_path, *extra: str) -> None:
    """Run `main()` as the CLI would, from an empty directory (it writes report.json)."""
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "run",
            "--personas", str(REAL_PERSONAS_DIR),
            "--model", "skip-blocked-nearest-deadline",
            "--model-commit", "a" * 40,
            "--persona-set-commit", "b" * 40,
            *extra,
        ],
    )
    main()


def test_a_run_that_passes_its_gates_writes_a_record_that_loads_back(
    monkeypatch, tmp_path, capsys
):
    record = tmp_path / "baseline.json"

    _run_cli(monkeypatch, tmp_path, "--record-baseline", str(record), "--min-accuracy", "0.1")

    assert "Baseline recorded to" in capsys.readouterr().out
    report = load_baseline_record(
        record, personas_path=REAL_PERSONAS_DIR, hierarchy_dir=REAL_HIERARCHY_DIR
    )
    assert report.model_name == "SkipBlockedNearestDeadlineModel"


def test_the_report_has_the_original_set_the_hierarchy_group_and_everything(
    monkeypatch, tmp_path, capsys
):
    # The three numbers #507 asks for, each as one line: the set before the
    # hierarchy group, the hierarchy group alone, and all of it together.
    _run_cli(monkeypatch, tmp_path, "--record-baseline", str(tmp_path / "baseline.json"))

    out = capsys.readouterr().out
    assert "[original set: all but hierarchy_decides]" in out
    report = EvaluationOutput.model_validate_json((tmp_path / "report.json").read_text())
    assert report.original_set is not None
    hierarchy = report.groups["hierarchy_decides"]
    assert report.original_set.total_scenarios + hierarchy.total_scenarios == (
        report.combined.total_scenarios
    )
    assert report.original_set.correct_top1 + hierarchy.correct_top1 == (
        report.combined.correct_top1
    )


def test_the_record_stores_team_relative_paths_not_the_recorders_machine_layout(
    monkeypatch, tmp_path
):
    # _run_cli passes --personas as an absolute path and leaves --hierarchy at its
    # default, which is absolute too; the committed record must still read the same
    # on every machine.
    record = tmp_path / "baseline.json"

    _run_cli(monkeypatch, tmp_path, "--record-baseline", str(record))

    stored = json.loads(record.read_text(encoding="utf-8"))["persona_set"]
    assert stored["path"] == "eval/personas"
    assert stored["hierarchy_path"] == "eval/hierarchy"


def _broken_hierarchy(tmp_path: Path, nodes: dict) -> Path:
    hierarchy = tmp_path / "hierarchy"
    hierarchy.mkdir()
    (hierarchy / NODES_FILENAME).write_text(json.dumps(nodes), encoding="utf-8")
    return hierarchy


@pytest.mark.parametrize(
    ("nodes", "message"),
    [
        # A node file that does not load at all (importance out of range).
        (
            {"nodes": [{"task_id": "x", "parent_task_id": None,
                        "importance": 2.0, "label": "X"}]},
            "Could not load the hierarchy",
        ),
        # A node file that loads (the shared model allows cycles) but the eval does not.
        (
            {"nodes": [{"task_id": "loop", "parent_task_id": "loop",
                        "importance": 0.5, "label": "Loop"}]},
            "Hierarchy validation FAILED",
        ),
        # A node file that loads, but the scenarios' parents are not in it.
        (
            {"nodes": [{"task_id": "unrelated", "parent_task_id": None,
                        "importance": 0.5, "label": "Unrelated"}]},
            "Hierarchy validation FAILED",
        ),
    ],
)
def test_a_broken_hierarchy_fails_the_run_before_scoring(
    monkeypatch, tmp_path, capsys, nodes, message
):
    hierarchy = _broken_hierarchy(tmp_path, nodes)
    record = tmp_path / "baseline.json"

    with pytest.raises(SystemExit) as exit_info:
        _run_cli(
            monkeypatch, tmp_path, "--hierarchy", str(hierarchy),
            "--record-baseline", str(record),
        )

    assert exit_info.value.code == 1
    assert not record.exists()
    captured = capsys.readouterr()
    assert message in captured.out + captured.err
    assert "Evaluation Summary" not in captured.out


def test_a_node_file_that_is_not_utf8_gets_the_message_not_a_traceback(
    monkeypatch, tmp_path, capsys
):
    hierarchy = tmp_path / "hierarchy"
    hierarchy.mkdir()
    (hierarchy / NODES_FILENAME).write_bytes(b'{"nodes": [{"label": "\xff\xfe"}]}')

    # SystemExit(1), not UnicodeDecodeError escaping from main().
    with pytest.raises(SystemExit) as exit_info:
        _run_cli(
            monkeypatch, tmp_path, "--hierarchy", str(hierarchy),
            "--record-baseline", str(tmp_path / "baseline.json"),
        )

    assert exit_info.value.code == 1
    assert "Could not load the hierarchy" in capsys.readouterr().err


@pytest.mark.parametrize(
    "content",
    [
        b'{"scenario_id": "\xff\xfe broken"}',  # not valid UTF-8
        b'{"scenario_id": "developer_cut_off", ',  # valid UTF-8, not valid JSON
    ],
)
def test_a_scenario_file_that_cannot_be_read_gets_the_message_not_a_traceback(
    monkeypatch, tmp_path, capsys, content
):
    personas = tmp_path / "personas"
    personas.mkdir()
    (personas / "developer_unreadable.json").write_bytes(content)

    # The later --personas overrides the one _run_cli passes.
    with pytest.raises(SystemExit) as exit_info:
        _run_cli(
            monkeypatch, tmp_path, "--personas", str(personas),
            "--record-baseline", str(tmp_path / "baseline.json"),
        )

    assert exit_info.value.code == 1
    err = capsys.readouterr().err
    assert "Could not load personas" in err
    assert "developer_unreadable.json" in err


@pytest.mark.parametrize(
    "gate",
    [
        ("--min-accuracy", "0.99"),  # the model scores far below this
        ("--max-accuracy", "0.01"),  # ... and far above this
    ],
)
def test_a_run_that_fails_an_accuracy_gate_leaves_no_record_behind(
    monkeypatch, tmp_path, capsys, gate
):
    # The record is write-once, so a failed (regression) run that still wrote it would
    # also block the path for the real run that follows.
    record = tmp_path / "baseline.json"

    with pytest.raises(SystemExit) as exit_info:
        _run_cli(monkeypatch, tmp_path, "--record-baseline", str(record), *gate)

    assert exit_info.value.code == 1
    assert not record.exists()
    assert "Baseline recorded" not in capsys.readouterr().out


def test_an_existing_record_stops_the_run_before_scoring_and_is_left_untouched(
    monkeypatch, tmp_path, capsys
):
    record = tmp_path / "baseline.json"
    record.write_text("already here", encoding="utf-8")

    with pytest.raises(SystemExit) as exit_info:
        _run_cli(monkeypatch, tmp_path, "--record-baseline", str(record))

    assert exit_info.value.code == 1
    assert record.read_text(encoding="utf-8") == "already here"
    assert BASELINE_EXISTS_MESSAGE.format(path=record) in capsys.readouterr().err
