import numpy as np
import pytest
from test_timeline import _dataset, generated, ts  # noqa: F401  (generated is a fixture)

from flow_health.features import build_timeline, edge_features
from flow_health.features.edge_features import EDGE_COLUMNS


def _edges(ds):
    return edge_features(ds, build_timeline(ds)).set_index(["task_id", "depends_on_task_id"])


@pytest.fixture
def history():
    """B waits on A (A done on day 5); C's edge to B is removed on day 4 and added again on day 6. as_of = day 10."""
    return _dataset(
        tasks=[("A", ts(1)), ("B", ts(1)), ("C", ts(2))],
        states=[("A", "STATUS_CHANGED", "PENDING", "ACTIVE", ts(2)),
                ("B", "BLOCKED", None, None, ts(1, 1)),
                ("C", "BLOCKED", None, None, ts(2, 1)),
                ("C", "UNBLOCKED", None, None, ts(4)),
                ("A", "STATUS_CHANGED", "ACTIVE", "DONE", ts(5)),
                ("B", "UNBLOCKED", None, None, ts(5)),
                ("C", "BLOCKED", None, None, ts(6))],
        deps=[("B", "A", "ADDED", ts(1, 1)), ("C", "B", "ADDED", ts(2, 1)),
              ("C", "B", "REMOVED", ts(4)), ("C", "B", "ADDED", ts(6))])


def test_a_toggled_edge(history):
    cb = _edges(history).loc[("C", "B")]
    assert cb.n_toggles == 3
    assert cb.median_gap_days == pytest.approx(((4 - 2 - 1 / 24) + 2) / 2)  # gaps: 1d23h and 2d
    active = (4 - 2 - 1 / 24) + (10 - 6)
    assert cb.active_fraction == pytest.approx(active / (10 - 2 - 1 / 24))
    assert cb.n_dependent_flips == 3  # blocked by the add, freed by the removal, blocked again by the re-add


def test_a_quiet_edge_has_no_gap_and_is_active_all_the_time(history):
    ba = _edges(history).loc[("B", "A")]
    assert ba.n_toggles == 1 and np.isnan(ba.median_gap_days) and ba.active_fraction == 1.0
    assert ba.n_dependent_flips == 2  # blocked when added, freed when A finished


def test_one_row_per_pair_however_often_it_toggles(history):
    assert len(_edges(history)) == 2


def test_the_window_of_an_edge_ends_when_its_dependent_is_done():
    ds = _dataset(
        tasks=[("A", ts(1)), ("T", ts(1))],
        states=[("T", "STATUS_CHANGED", "PENDING", "ACTIVE", ts(2)), ("T", "STATUS_CHANGED", "ACTIVE", "DONE", ts(5))],
        deps=[("T", "A", "ADDED", ts(1)), ("T", "A", "REMOVED", ts(3)), ("T", "A", "ADDED", ts(4))])
    ta = _edges(ds).loc[("T", "A")]
    assert ta.active_fraction == pytest.approx((2 + 1) / 4)  # day 1-3 and day 4-5, window day 1-5


def test_generated_history_gives_one_finite_row_per_pair(generated):  # noqa: F811
    tl = build_timeline(generated)
    f = edge_features(generated, tl)
    assert list(f.columns) == EDGE_COLUMNS
    pairs = tl.edge_intervals[["task_id", "depends_on_task_id"]].drop_duplicates()
    assert len(f) == len(pairs) and not f.duplicated(["task_id", "depends_on_task_id"]).any()
    assert f["active_fraction"].between(0, 1).all() and (f["n_toggles"] >= 1).all()
    assert f["median_gap_days"].notna().sum() == (f["n_toggles"] >= 2).sum()
    assert np.isfinite(f.drop(columns=["task_id", "depends_on_task_id", "median_gap_days"]).to_numpy(float)).all()
