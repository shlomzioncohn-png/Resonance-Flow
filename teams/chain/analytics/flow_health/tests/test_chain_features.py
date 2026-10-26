import numpy as np
import pytest
from test_timeline import _dataset, generated, ts  # noqa: F401  (generated is a fixture)

from flow_health.features import build_timeline, chain_features
from flow_health.features.chain_features import CHAIN_COLUMNS, length_bucket


def _chains(ds):
    return chain_features(ds, build_timeline(ds)).set_index("sink_task_id")


def _worked(task, active, done):
    return [(task, "STATUS_CHANGED", "PENDING", "ACTIVE", ts(*active)),
            (task, "STATUS_CHANGED", "ACTIVE", "DONE", ts(*done))]


@pytest.fixture
def graph():
    """A <- B <- C <- D (D waits on C, C on B, B on A), E waits on B, F waits on A. All edges exist from day 1."""
    states = (_worked("A", (1,), (2,)) + _worked("B", (2,), (4,)) + _worked("C", (4,), (5,)) + _worked("D", (5,), (9,))
              + _worked("E", (5,), (10,)) + _worked("F", (2,), (3,)))
    deps = [(t, d, "ADDED", ts(1)) for t, d in (("B", "A"), ("C", "B"), ("D", "C"), ("E", "B"), ("F", "A"))]
    return _dataset(tasks=[(t, ts(1)) for t in "ABCDEF"], states=states, deps=deps, as_of=ts(20))


def test_a_chain_is_the_longest_path_behind_a_finished_sink(graph):
    c = _chains(graph)
    assert set(c.index) == {"D", "E"}  # F's path is only 2 tasks long; A, B, C have dependents
    d = c.loc["D"]
    assert (d.task_ids, d.root_task_id, d.chain_length, d.length_bucket) == ("A;B;C;D", "A", 4, "4")


def test_hops_run_done_to_done_after_the_roots_own_work_and_add_up_to_end_to_end(graph):
    d = _chains(graph).loc["D"]
    hops = [float(h) for h in d.hop_days.split(";")]
    assert hops == pytest.approx([1, 2, 1, 4]) and d.end_to_end_days == pytest.approx(8)
    assert sum(hops) == pytest.approx(d.end_to_end_days)
    assert (d.mean_hop, d.max_hop, d.max_hop_share) == pytest.approx((2, 4, 0.5))
    assert d.std_hop == pytest.approx(np.std([1, 2, 1, 4]))
    e = _chains(graph).loc["E"]
    assert e.task_ids == "A;B;E" and [float(h) for h in e.hop_days.split(";")] == pytest.approx([1, 2, 6])


def test_a_tie_between_equally_long_paths_goes_to_the_smaller_id():
    states = _worked("R", (1,), (2,)) + _worked("P", (2,), (3,)) + _worked("Q", (2,), (3, 1)) + _worked("Z", (4,), (6,))
    deps = [(t, d, "ADDED", ts(1)) for t, d in (("P", "R"), ("Q", "R"), ("Z", "P"), ("Z", "Q"))]
    ds = _dataset(tasks=[(t, ts(1)) for t in "RPQZ"], states=states, deps=deps, as_of=ts(20))
    assert _chains(ds).loc["Z", "task_ids"] == "R;P;Z"


def test_only_edges_active_when_the_sink_finished_count(graph):
    graph.dependency_history.loc[len(graph.dependency_history)] = ["D", "C", "REMOVED", ts(7), 99]  # removed before D's end
    assert "D" not in _chains(graph).index  # D now waits on nothing: no chain of 3


def test_edge_changes_along_the_chain_are_counted(graph):
    h = graph.dependency_history
    h.loc[len(h)] = ["C", "B", "REMOVED", ts(3), 98]
    h.loc[len(h)] = ["C", "B", "ADDED", ts(3, 6), 99]
    assert _chains(graph).loc["D", "n_edge_changes"] == 3 + 2


def test_length_buckets():
    assert [length_bucket(n) for n in (3, 4, 5, 6, 7, 8, 12)] == ["3", "4", "5", "6", "7", "8+", "8+"]


def test_generated_history_chains_are_valid(generated):  # noqa: F811
    tl = build_timeline(generated)
    c = chain_features(generated, tl)
    assert list(c.columns) == CHAIN_COLUMNS and len(c) > 0 and c["sink_task_id"].is_unique
    assert (c["chain_length"] >= 3).all()
    hops = c["hop_days"].map(lambda s: [float(x) for x in s.split(";")])
    assert (hops.map(len) == c["chain_length"]).all()
    assert np.allclose(hops.map(sum), c["end_to_end_days"], atol=1e-6)
    assert (c["task_ids"].str.count(";") + 1 == c["chain_length"]).all()
    assert c["max_hop_share"].between(0, 1).all()
    done = set(tl.status_segments.loc[tl.status_segments["status"] == "DONE", "task_id"])
    assert all(set(ids.split(";")) <= done for ids in c["task_ids"])
