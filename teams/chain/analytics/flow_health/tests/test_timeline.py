import logging
from dataclasses import replace
from datetime import datetime, timedelta, timezone

import pandas as pd
import pytest

from flow_health.features import build_timeline
from flow_health.generator import GeneratorConfig, generate
from flow_health.generator.export import write_dataset
from flow_health.loaders import CsvLoader, Dataset


def ts(day, hour=0):
    return pd.Timestamp(datetime(2026, 1, day, hour, tzinfo=timezone.utc))


AS_OF = ts(10)


def _dataset(tasks, states, deps, as_of=AS_OF):
    """tasks: [(id, created)]; states: [(task, event, from, to, at)]; deps: [(task, depends_on, action, at)]."""
    return Dataset(
        tasks=pd.DataFrame([(t, c, 1) for t, c in tasks], columns=["task_id", "created_at", "project_id"]),
        task_meta=pd.DataFrame({"task_id": [t for t, _ in tasks], "tier": "operational"}),
        dependency_history=pd.DataFrame(
            [(t, d, a, at, i + 1) for i, (t, d, a, at) in enumerate(deps)],
            columns=["task_id", "depends_on_task_id", "action", "created_at", "sequence"]),
        task_state_history=pd.DataFrame(
            [(t, e, f, to, at, i + 1) for i, (t, e, f, to, at) in enumerate(states)],
            columns=["task_id", "event_type", "from_status", "to_status", "created_at", "sequence"]),
        as_of=as_of)


def _rows(df):
    return [tuple(r) for r in df.itertuples(index=False)]


@pytest.fixture
def three_tasks():
    """A <- B <- C (B depends on A, C depends on B). A finishes on day 5, B starts on day 6, C stays blocked."""
    return _dataset(
        tasks=[("A", ts(1)), ("B", ts(1)), ("C", ts(2))],
        states=[("A", "STATUS_CHANGED", "PENDING", "ACTIVE", ts(2)),
                ("B", "BLOCKED", None, None, ts(1, 1)),
                ("C", "BLOCKED", None, None, ts(2, 1)),
                ("A", "STATUS_CHANGED", "ACTIVE", "DONE", ts(5)),
                ("B", "UNBLOCKED", None, None, ts(5)),
                ("B", "STATUS_CHANGED", "PENDING", "ACTIVE", ts(6))],
        deps=[("B", "A", "ADDED", ts(1, 1)), ("C", "B", "ADDED", ts(2, 1))])


def test_status_segments_match_the_hand_written_history(three_tasks):
    t = build_timeline(three_tasks)
    assert _rows(t.status_segments) == [
        ("A", "PENDING", ts(1), ts(2), False),
        ("A", "ACTIVE", ts(2), ts(5), False),
        ("A", "DONE", ts(5), ts(5), False),
        ("B", "PENDING", ts(1), ts(6), False),
        ("B", "ACTIVE", ts(6), AS_OF, True),  # still running: cut at as_of
        ("C", "PENDING", ts(2), AS_OF, True),
    ]
    assert t.warnings == []


def test_a_block_still_open_is_cut_at_as_of_and_marked_open(three_tasks):
    assert _rows(build_timeline(three_tasks).block_segments) == [
        ("B", ts(1, 1), ts(5), False),
        ("C", ts(2, 1), AS_OF, True),
    ]


def test_edges_are_active_from_added_until_removed_or_as_of():
    ds = _dataset(
        tasks=[("A", ts(1)), ("B", ts(1)), ("C", ts(2))], states=[],
        deps=[("B", "A", "ADDED", ts(1, 1)), ("C", "B", "ADDED", ts(2, 1)),
              ("C", "B", "REMOVED", ts(4)), ("C", "B", "ADDED", ts(6))])
    assert _rows(build_timeline(ds).edge_intervals) == [
        ("B", "A", ts(1, 1), AS_OF, True),
        ("C", "B", ts(2, 1), ts(4), False),
        ("C", "B", ts(6), AS_OF, True),
    ]


def test_a_contradicting_event_is_a_warning_not_a_crash_and_not_silent(caplog):
    ds = _dataset(
        tasks=[("A", ts(1)), ("B", ts(1))],
        states=[("A", "UNBLOCKED", None, None, ts(2)),  # nothing was blocked
                ("B", "STATUS_CHANGED", "PENDING", "ACTIVE", ts(2)),
                ("B", "STATUS_CHANGED", "ACTIVE", "DONE", ts(3)),
                ("B", "STATUS_CHANGED", "DONE", "ACTIVE", ts(4))],  # DONE never goes back
        deps=[("B", "A", "REMOVED", ts(2))])  # never added
    with caplog.at_level(logging.WARNING):
        t = build_timeline(ds)
    assert len(t.warnings) == 3
    assert "UNBLOCKED" in caplog.text and "after DONE" in caplog.text and "REMOVED" in caplog.text
    assert t.block_segments.empty and t.edge_intervals.empty
    assert _rows(t.status_segments)[-1] == ("B", "DONE", ts(3), ts(3), False)  # the reopen was skipped


def test_a_double_blocked_keeps_the_first_start():
    ds = _dataset(tasks=[("A", ts(1))],
                  states=[("A", "BLOCKED", None, None, ts(2)), ("A", "BLOCKED", None, None, ts(3))], deps=[])
    t = build_timeline(ds)
    assert _rows(t.block_segments) == [("A", ts(2), AS_OF, True)] and len(t.warnings) == 1


@pytest.fixture(scope="module")
def generated(tmp_path_factory):
    out = tmp_path_factory.mktemp("small")
    data = generate(replace(GeneratorConfig(), n_normal_tasks=400, n_projects=6, instances_per_severity=1))
    write_dataset(out, data.config, data.world, data.history)
    return CsvLoader(out).load()


def test_generated_history_has_no_contradictions(generated):
    assert build_timeline(generated).warnings == []


def test_every_task_has_contiguous_status_segments_ending_open_or_done(generated):
    t = build_timeline(generated)
    for task_id, g in t.status_segments.groupby("task_id"):
        rows = _rows(g)
        assert all(a[3] == b[2] for a, b in zip(rows, rows[1:])), task_id
        last = rows[-1]
        assert (last[1] == "DONE") != last[4], task_id  # DONE closes it; otherwise it is open at as_of
    assert set(t.status_segments["task_id"]) == set(generated.tasks["task_id"])


def test_block_and_edge_segments_never_end_before_they_start_and_open_ones_end_at_as_of(generated):
    t = build_timeline(generated)
    for df in (t.block_segments, t.edge_intervals):
        assert (df["end"] >= df["start"]).all()
        assert (df.loc[df["is_open"], "end"] == generated.as_of).all()
