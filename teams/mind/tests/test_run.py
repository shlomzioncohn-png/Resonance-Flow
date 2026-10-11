import sys
from pathlib import Path

import pytest

from mind.evaluation.baseline_record import BASELINE_EXISTS_MESSAGE, load_baseline_record
from mind.evaluation.harness import CandidateScore, EvaluationOutput, Report, ScenarioResult
from mind.evaluation.interfaces import ScoreFactors
from mind.evaluation.run import _report_json, main

REAL_PERSONAS_DIR = Path(__file__).resolve().parents[1] / "eval" / "personas"


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
    report = load_baseline_record(record, personas_path=REAL_PERSONAS_DIR)
    assert report.model_name == "SkipBlockedNearestDeadlineModel"


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
