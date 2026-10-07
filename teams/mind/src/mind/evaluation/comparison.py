"""Baseline-vs-stretch comparison: the mechanism, built before either model exists.

Week 7 needs to answer the mentor's question — known in advance (see
`teams/mind/CLAUDE.md` Part 3, "Stretch scoring"): *"show me one scenario where
the hierarchy stretch goal changed the recommendation vs. the baseline, and one
where it didn't — why?"* `diff_reports` takes the baseline `Report` (R2's
segmented ranking model, scored in week 5) and the stretch `Report` (R3's hierarchical
propagation model, week 7) — both produced by `harness.run` against the same
`eval/personas/` set — and turns them into the evidence that question needs,
so week 7 is "plug in the model and read the diff" instead of inventing the
comparison under a deadline.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, get_args

from mind.evaluation.harness import Report, ScenarioResult

ScenarioChangeDirection = Literal[
    "wrong_to_right", "right_to_wrong", "wrong_to_different_wrong"
]

# Read from the Literal itself, so adding a direction there can't be missed here.
_ALL_DIRECTIONS: tuple[ScenarioChangeDirection, ...] = get_args(ScenarioChangeDirection)


@dataclass
class ScenarioDiff:
    """Baseline-vs-stretch outcome for one scenario, matched by scenario_id.

    - `changed`: whether the model's top-scored task differs between the two runs.
    - `direction`: only set when `changed` is True. One of `wrong_to_right`
      (the stretch model fixed a miss), `right_to_wrong` (it broke something the
      baseline had correct), or `wrong_to_different_wrong` (both wrong, but a
      different task) — the direction, not just the fact, is what answers the
      mentor. `None` when `changed` is False.
    - `score_deltas`: `stretch score - baseline score`, per task_id (both runs
      must have scored the same tasks) — "how much the scores moved", not just
      which task ended up on top.
    """

    scenario_id: str
    expected_top_task_id: str
    baseline_predicted_top_task_id: str
    stretch_predicted_top_task_id: str
    changed: bool
    direction: ScenarioChangeDirection | None
    score_deltas: dict[str, float]


@dataclass
class ComparisonReport:
    """Aggregate baseline-vs-stretch diff over a matched scenario set.

    `direction_counts` always carries all three direction keys (0 if a
    direction didn't occur) — the mentor's question isn't answered by "5
    scenarios changed" alone, it needs "3 wrong->right, 1 right->wrong, 1
    wrong->a different wrong".
    """

    baseline_model_name: str
    stretch_model_name: str
    total_scenarios: int
    baseline_accuracy: float
    stretch_accuracy: float
    changed_count: int
    direction_counts: dict[ScenarioChangeDirection, int]
    scenario_diffs: list[ScenarioDiff]


def _direction(
    *, baseline_passed: bool, stretch_passed: bool, top_changed: bool
) -> ScenarioChangeDirection | None:
    if not top_changed:
        return None
    if not baseline_passed and stretch_passed:
        return "wrong_to_right"
    if baseline_passed and not stretch_passed:
        return "right_to_wrong"
    if not baseline_passed and not stretch_passed:
        return "wrong_to_different_wrong"
    # baseline_passed and stretch_passed but top_changed: a scenario has exactly one
    # expected_top_task_id, so two passing results can only ever share it.
    raise AssertionError(
        "both baseline and stretch passed but their predicted top task differs — "
        "should be structurally impossible"
    )


def _score_by_task(result: ScenarioResult) -> dict[str, float]:
    return {c.task_id: c.score for c in result.candidates}


def _by_scenario_id(report: Report, label: str) -> dict[str, ScenarioResult]:
    """Index a report by `scenario_id`, refusing duplicates: a dict would keep
    only the last one and silently compare fewer scenarios than the report holds."""
    by_id: dict[str, ScenarioResult] = {}
    for result in report.scenario_results:
        if result.scenario_id in by_id:
            raise ValueError(f"{label} report has scenario_id {result.scenario_id!r} twice")
        by_id[result.scenario_id] = result
    return by_id


def diff_reports(baseline: Report, stretch: Report) -> ComparisonReport:
    """Per-scenario baseline-vs-stretch diff, matched by `scenario_id`.

    Scenarios are matched by `scenario_id`, not list position, so the two
    `Report` objects don't need to have been produced in the same order. Both
    reports must cover exactly the same `scenario_id` set — comparing
    partially-overlapping sets would silently understate "what changed", so a
    mismatch raises `ValueError` instead of comparing whatever overlaps. The
    same holds inside a scenario: both reports must carry the same
    `expected_top_task_id` and have scored the same tasks, or it isn't the same
    scenario, and that raises `ValueError` too — as does a `scenario_id` that
    appears twice in one report.
    """
    baseline_by_id = _by_scenario_id(baseline, "baseline")
    stretch_by_id = _by_scenario_id(stretch, "stretch")

    if baseline_by_id.keys() != stretch_by_id.keys():
        only_baseline = sorted(baseline_by_id.keys() - stretch_by_id.keys())
        only_stretch = sorted(stretch_by_id.keys() - baseline_by_id.keys())
        raise ValueError(
            "baseline and stretch reports cover different scenario sets — "
            f"only in baseline: {only_baseline}; only in stretch: {only_stretch}"
        )

    direction_counts: dict[ScenarioChangeDirection, int] = {d: 0 for d in _ALL_DIRECTIONS}
    scenario_diffs: list[ScenarioDiff] = []

    for scenario_id in sorted(baseline_by_id):
        base_result = baseline_by_id[scenario_id]
        stretch_result = stretch_by_id[scenario_id]

        if base_result.expected_top_task_id != stretch_result.expected_top_task_id:
            raise ValueError(
                f"{scenario_id}: baseline expects {base_result.expected_top_task_id!r} "
                f"but stretch expects {stretch_result.expected_top_task_id!r} — the same "
                "scenario_id must mean the same scenario in both reports"
            )

        top_changed = base_result.predicted_top_task_id != stretch_result.predicted_top_task_id
        direction = _direction(
            baseline_passed=base_result.passed,
            stretch_passed=stretch_result.passed,
            top_changed=top_changed,
        )
        if direction is not None:
            direction_counts[direction] += 1

        base_scores = _score_by_task(base_result)
        stretch_scores = _score_by_task(stretch_result)
        if base_scores.keys() != stretch_scores.keys():
            raise ValueError(
                f"{scenario_id}: baseline and stretch scored different task sets — "
                f"only in baseline: {sorted(base_scores.keys() - stretch_scores.keys())}; "
                f"only in stretch: {sorted(stretch_scores.keys() - base_scores.keys())}"
            )

        scenario_diffs.append(
            ScenarioDiff(
                scenario_id=scenario_id,
                expected_top_task_id=base_result.expected_top_task_id,
                baseline_predicted_top_task_id=base_result.predicted_top_task_id,
                stretch_predicted_top_task_id=stretch_result.predicted_top_task_id,
                changed=top_changed,
                direction=direction,
                score_deltas={t: stretch_scores[t] - base_scores[t] for t in base_scores},
            )
        )

    return ComparisonReport(
        baseline_model_name=baseline.model_name,
        stretch_model_name=stretch.model_name,
        total_scenarios=len(scenario_diffs),
        baseline_accuracy=baseline.top1_accuracy,
        stretch_accuracy=stretch.top1_accuracy,
        changed_count=sum(d.changed for d in scenario_diffs),
        direction_counts=direction_counts,
        scenario_diffs=scenario_diffs,
    )
