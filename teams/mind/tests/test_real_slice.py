"""The real-data slice (eval/real_slice/) must stay loadable and runnable.

Foundation Gate: the harness runs on hand-written personas *and* on a slice of a
real corpus (AMI meeting action items). These tests pin that second half, so a
malformed AMI-derived scenario fails CI instead of only surfacing when someone
runs the slice by hand. See src/mind/evaluation/README.md -> "Real data slice".
"""

from pathlib import Path

from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import run
from mind.evaluation.personas import load_personas, validate_personas

REAL_SLICE_DIR = Path(__file__).resolve().parents[1] / "eval" / "real_slice"


def test_real_slice_scenarios_load():
    scenarios = load_personas(REAL_SLICE_DIR)
    assert len(scenarios) == 3
    # Every real-slice scenario is derived from AMI and must say which meeting.
    assert all(s.source.type == "AMI" for s in scenarios)
    assert all(s.source.source_detail for s in scenarios)


def test_real_slice_has_no_hard_validation_errors():
    # Coverage / count warnings are expected for the slice; hard errors are not.
    report = validate_personas(load_personas(REAL_SLICE_DIR))
    assert report.ok, report.errors


def test_real_slice_runs_through_the_harness():
    # DummyModel scores every task 1.0, so the harness's tie-break (first task
    # in file order wins) makes its prediction fully deterministic per
    # scenario. Pinning it here means an edited expected_top_task_id, a
    # reordered tasks list, or a dropped scenario fails this test instead of
    # only surfacing when someone runs the slice by hand.
    scenarios = load_personas(REAL_SLICE_DIR)
    report = run(DummyModel(), scenarios)

    assert report.total_scenarios == 3
    assert report.correct_top1 == 2

    predicted = {r.scenario_id: r.predicted_top_task_id for r in report.scenario_results}
    assert predicted == {
        "manager_ami_ib4003_office_move": "task_walls_feasibility",
        "manager_ami_is1008b_functional_design": "task_components_concept",
        "manager_ami_is1008d_budget_close": "task_negotiate_cost",
    }
