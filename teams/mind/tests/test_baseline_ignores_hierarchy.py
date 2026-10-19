"""The baseline reads no hierarchy, and the week-7 "designed to change" case stays open.

The parent hierarchy is the stretch's input (#507, #474). A baseline that read it
would leave nothing for the stretch to be measured against, so the mentor decided
the baseline must not (#797 removed its parent signal). These tests keep it that
way on the real set:

- `SegmentedRankingModel` gives the same pick and the same score for every
  candidate whether `parent_task_id` is set or not;
- it misses `manager_restructuring_team_signoff_vs_maintenance_team_signoff`, the
  "designed to change" scenario. The margin there is small by design (0.7172 vs
  0.7160), so a small weight change could quietly make the baseline right on it —
  which is how the first pick, `manager_status_digest_vs_reorg_proposal`, stopped
  being a case the stretch could change.
"""

import pytest

from mind.evaluation.harness import Report, run
from mind.evaluation.personas import PERSONAS_DIR, load_personas
from mind.scoring.segmented_model import SegmentedRankingModel

DESIGNED_TO_CHANGE = "manager_restructuring_team_signoff_vs_maintenance_team_signoff"


def _without_parents(scenarios):
    copies = [s.model_copy(deep=True) for s in scenarios]
    for scenario in copies:
        for task in scenario.tasks:
            task.parent_task_id = None
    return copies


@pytest.fixture(scope="module")
def runs() -> tuple[Report, Report]:
    scenarios = load_personas(PERSONAS_DIR)
    assert any(t.parent_task_id is not None for s in scenarios for t in s.tasks)
    model = SegmentedRankingModel()
    return run(model, scenarios), run(model, _without_parents(scenarios))


def test_baseline_gives_identical_results_with_and_without_parents(runs):
    with_parents, without = runs

    for a, b in zip(with_parents.scenario_results, without.scenario_results, strict=True):
        assert a.scenario_id == b.scenario_id
        assert a.predicted_top_task_id == b.predicted_top_task_id, a.scenario_id
        assert [(c.task_id, c.score) for c in a.candidates] == [
            (c.task_id, c.score) for c in b.candidates
        ], a.scenario_id


def test_baseline_misses_the_designed_to_change_scenario(runs):
    with_parents, _ = runs
    result = next(r for r in with_parents.scenario_results if r.scenario_id == DESIGNED_TO_CHANGE)

    assert not result.passed, (
        f"the baseline now gets {DESIGNED_TO_CHANGE} right, so the stretch can no longer "
        "show a change there; pick another 'designed to change' scenario (README, Hierarchy)"
    )
