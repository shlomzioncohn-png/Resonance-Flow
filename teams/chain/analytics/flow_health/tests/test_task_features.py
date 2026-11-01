import numpy as np
import pandas as pd
import pytest
from test_timeline import AS_OF, _dataset, generated, ts  # noqa: F401  (generated is a fixture)

from flow_health.features import build_timeline
from flow_health.features.task_features import TASK_COLUMNS, UNKNOWN_TIER, task_features


def _project_ratio(tasks):
    """The project-and-tier ratio computed on a hand-made table (the same code the feature uses)."""
    from flow_health.features.task_features import project_ratio
    return project_ratio(tasks)


def _features(ds):
    return task_features(ds, build_timeline(ds)).set_index("task_id")


@pytest.fixture
def three():
    """A <- B <- C: A is done on day 5, B starts on day 6, C stays blocked behind B (as_of = day 10)."""
    return _dataset(
        tasks=[("A", ts(1)), ("B", ts(1)), ("C", ts(2))],
        states=[("A", "STATUS_CHANGED", "PENDING", "ACTIVE", ts(2)),
                ("B", "BLOCKED", None, None, ts(1, 1)),
                ("C", "BLOCKED", None, None, ts(2, 1)),
                ("A", "STATUS_CHANGED", "ACTIVE", "DONE", ts(5)),
                ("B", "UNBLOCKED", None, None, ts(5)),
                ("B", "STATUS_CHANGED", "PENDING", "ACTIVE", ts(6))],
        deps=[("B", "A", "ADDED", ts(1, 1)), ("C", "B", "ADDED", ts(2, 1))])


def test_durations_counts_and_flags_on_the_hand_written_history(three):
    f = _features(three)
    block_b, block_c = 4 - 1 / 24, 8 - 1 / 24
    a, b, c = f.loc["A"], f.loc["B"], f.loc["C"]
    assert (a.age_days, a.days_pending, a.days_active, bool(a.is_open)) == (4, 1, 3, False)
    assert (b.age_days, b.days_pending, b.days_active, bool(b.is_open)) == (9, 5, 4, True)  # open: cut at as_of
    assert (c.age_days, c.days_pending, c.days_active, bool(c.is_open)) == (8, 8, 0, True)
    assert b.days_blocked_total == pytest.approx(block_b) and b.n_block_events == 1
    assert c.days_blocked_total == pytest.approx(block_c)  # the open block is counted up to as_of
    assert (b.blocked_p50, b.blocked_p90, b.blocked_max) == pytest.approx((block_b,) * 3)
    assert np.isnan(a.blocked_p50) and a.days_blocked_total == 0 and a.n_block_events == 0
    assert [f.loc[t, "n_dependents_now"] for t in "ABC"] == [1, 1, 0]
    assert [f.loc[t, "n_dependents_max"] for t in "ABC"] == [1, 1, 0]
    assert a.dependents_per_30d == pytest.approx(30 / 4) and c.dependents_per_30d == 0


def test_ratios_divide_by_the_median_of_the_whole_tier_open_tasks_included(three):
    f = _features(three)
    assert f["age_ratio_to_tier"].tolist() == pytest.approx([4 / 8, 9 / 8, 1.0])  # median age of [4, 9, 8]
    block_b, block_c = 4 - 1 / 24, 8 - 1 / 24
    median_of_blocked = (block_b + block_c) / 2  # A was never blocked, so it is not in the median
    assert f["blocked_ratio_to_tier"].tolist() == pytest.approx([0, block_b / median_of_blocked, block_c / median_of_blocked])


def test_active_ratio_divides_work_time_by_the_median_of_tasks_that_were_worked_on(three):
    f = _features(three)  # ACTIVE days: A 3, B 4, C never ACTIVE -> median of the worked-on tasks is 3.5
    assert f["active_ratio_to_tier"].tolist() == pytest.approx([3 / 3.5, 4 / 3.5, 0.0])


def test_project_ratio_uses_the_median_of_the_same_tier_in_the_same_project(three):
    f = _features(three)  # one project and one tier here, so it equals the tier ratio
    assert f["active_ratio_to_tier_project"].tolist() == pytest.approx(f["active_ratio_to_tier"].tolist())


def test_project_ratio_compares_a_task_with_its_own_projects_pace():
    days = [1.0] * 5 + [4.0] * 5  # project 1 works in 1 day, project 2 in 4 days, same tier
    tasks = pd.DataFrame({"task_id": [f"t{i}" for i in range(10)], "tier": "operational", "days_active": days,
                          "project_id": [1] * 5 + [2] * 5, "active_ratio_to_tier": [d / 2.5 for d in days]})
    out = _project_ratio(tasks)
    assert out.tolist() == pytest.approx([1.0] * 10)  # each is exactly the usual of its project


def test_a_small_project_tier_cell_falls_back_to_the_tier_ratio():
    tasks = pd.DataFrame({"task_id": list("abc"), "tier": "operational", "days_active": [1.0, 2.0, 9.0],
                          "project_id": [1, 1, 2], "active_ratio_to_tier": [0.5, 1.0, 4.5]})
    assert _project_ratio(tasks).tolist() == pytest.approx([0.5, 1.0, 4.5])


def test_a_missing_tier_is_its_own_group_with_its_own_median():
    ds = _dataset(tasks=[("A", ts(1)), ("B", ts(2)), ("C", ts(3))], states=[], deps=[])
    ds.task_meta.loc[ds.task_meta["task_id"].isin(["B", "C"]), "tier"] = None
    f = _features(ds)
    assert f["tier"].tolist() == ["operational", UNKNOWN_TIER, UNKNOWN_TIER]
    assert f["age_ratio_to_tier"].tolist() == pytest.approx([1.0, 8 / 7.5, 7 / 7.5])  # ages 8 and 7: their median is 7.5


def test_the_block_median_of_a_tier_ignores_tasks_that_were_never_blocked():
    ds = _dataset(
        tasks=[("A", ts(1)), ("B", ts(1)), ("C", ts(1)), ("D", ts(1))],
        states=[("C", "BLOCKED", None, None, ts(2)), ("C", "UNBLOCKED", None, None, ts(4)),
                ("D", "BLOCKED", None, None, ts(2)), ("D", "UNBLOCKED", None, None, ts(8))], deps=[])
    f = _features(ds)
    assert f["blocked_ratio_to_tier"].tolist() == pytest.approx([0, 0, 0.5, 1.5])  # blocked 2 and 6 days: median 4


def test_a_tier_with_no_block_at_all_gets_zero_not_a_division_by_zero():
    ds = _dataset(tasks=[("A", ts(1)), ("B", ts(1))], states=[], deps=[])
    assert _features(ds)["blocked_ratio_to_tier"].tolist() == [0, 0]


def test_dependents_count_is_now_versus_the_most_there_ever_were_at_once():
    ds = _dataset(
        tasks=[("X", ts(1)), ("P", ts(1)), ("Q", ts(1)), ("R", ts(1))], states=[],
        deps=[("P", "X", "ADDED", ts(2)), ("Q", "X", "ADDED", ts(3)),
              ("P", "X", "REMOVED", ts(4)), ("R", "X", "ADDED", ts(5)), ("Q", "X", "REMOVED", ts(6))])
    x = _features(ds).loc["X"]
    assert (x.n_dependents_now, x.n_dependents_max) == (1, 2)


def test_generated_history_gives_one_finite_row_per_task(generated):  # noqa: F811
    f = task_features(generated, build_timeline(generated))
    assert list(f.columns) == TASK_COLUMNS and len(f) == len(generated.tasks) and f["task_id"].is_unique
    numeric = f.select_dtypes("number").drop(columns=["blocked_p50", "blocked_p90", "blocked_max"])
    assert np.isfinite(numeric.to_numpy()).all() and (numeric >= 0).all().all()
    never_blocked = f["n_block_events"] == 0
    assert f.loc[never_blocked, ["blocked_p50", "blocked_p90", "blocked_max"]].isna().all().all()
    assert f.loc[~never_blocked, ["blocked_p50", "blocked_p90", "blocked_max"]].notna().all().all()
    assert (f.loc[~f["is_open"], "age_days"] <= (generated.as_of - generated.tasks["created_at"].min()).total_seconds() / 86400).all()
