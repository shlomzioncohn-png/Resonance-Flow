from datetime import datetime, timedelta, timezone

import pandas as pd

from flow_health.detectors.chain_paths import PATH_COLUMNS, extract_paths
from flow_health.detectors.config import SlowChainConfig
from flow_health.features import Timeline

CFG = SlowChainConfig()
T0 = datetime(2026, 3, 1, tzinfo=timezone.utc)


def ts(day):
    return T0 + timedelta(days=day)


def _timeline(tasks, edges):
    """tasks: {id: (first_active_day or None, done_day or None)}; edges: [(dependent, target, added_day, removed_day or None)]."""
    status = []
    for task, (active, done) in tasks.items():
        if active is not None:
            status.append((task, "ACTIVE", ts(active), ts(done) if done is not None else ts(100), done is None))
        if done is not None:
            status.append((task, "DONE", ts(done), ts(done), False))
    status = pd.DataFrame(status, columns=["task_id", "status", "start", "end", "is_open"])
    edge_rows = [(d, t, ts(a), ts(r) if r is not None else ts(100), r is None) for d, t, a, r in edges]
    edge_rows = pd.DataFrame(edge_rows, columns=["task_id", "depends_on_task_id", "start", "end", "is_open"])
    return Timeline(status_segments=status, block_segments=pd.DataFrame(columns=["task_id", "start", "end", "is_open"]),
                    edge_intervals=edge_rows)


def _line(n, step=5):
    """t0 <- t1 <- ... : t0 works days 0..step, each next one is done `step` days after the previous."""
    tasks = {f"t{i}": (0 if i == 0 else i * step - 1, (i + 1) * step) for i in range(n)}
    return tasks, [(f"t{i}", f"t{i - 1}", 0, None) for i in range(1, n)]


def test_a_linear_chain_gives_its_whole_path_root_first_with_the_right_time():
    paths = extract_paths(_timeline(*_line(5)), CFG)
    assert list(paths.columns) == PATH_COLUMNS
    row = paths[paths.chain_length == 5].iloc[0]
    assert row.task_ids == "t0;t1;t2;t3;t4" and row.chain_length == 5 and row.sink_task_id == "t4"
    assert row.end_to_end_days == 25 and row.max_hop_share == 0.2  # five hops of five days


def test_shorter_than_four_tasks_is_left_out_and_a_longer_path_gives_every_window_of_four_to_eight():
    assert extract_paths(_timeline(*_line(3)), CFG).empty
    assert len(extract_paths(_timeline(*_line(8)), CFG)) == 5 + 4 + 3 + 2 + 1  # windows of 4, 5, 6, 7, 8 tasks
    nine = extract_paths(_timeline(*_line(9)), CFG)
    assert nine.chain_length.max() == 8 and len(nine) == 6 + 5 + 4 + 3 + 2
    assert "t0;t1;t2;t3;t4;t5;t6;t7" in set(nine.task_ids) and "t1;t2;t3;t4;t5;t6;t7;t8" in set(nine.task_ids)


def test_a_window_inside_a_long_path_is_timed_from_its_own_first_task():
    paths = extract_paths(_timeline(*_line(9)), CFG).set_index("task_ids")
    assert paths.loc["t5;t6;t7;t8", "end_to_end_days"] == 21 and paths.loc["t5;t6;t7;t8", "sink_task_id"] == "t8"


def test_the_guard_stops_a_graph_with_too_many_paths():
    import pytest
    with pytest.raises(ValueError):
        extract_paths(_timeline(*_line(5)), SlowChainConfig(max_paths=0))


def test_a_task_with_two_dependencies_gives_two_paths_that_share_a_tail():
    tasks, edges = _line(4)
    tasks["side"] = (0, 8)
    edges.append(("t3", "side", 0, None))  # t3 waits on t2 and on side
    paths = extract_paths(_timeline(tasks, edges), CFG)
    assert list(paths.task_ids) == ["t0;t1;t2;t3"]  # the side path has only 2 tasks


def test_a_fan_in_with_long_sides_gives_each_side():
    tasks = {f"a{i}": (0 if i == 0 else i * 4, (i + 1) * 5) for i in range(3)}
    tasks.update({f"b{i}": (0 if i == 0 else i * 4, (i + 1) * 5) for i in range(3)})
    tasks["join"] = (20, 30)
    edges = [("a1", "a0", 0, None), ("a2", "a1", 0, None), ("b1", "b0", 0, None), ("b2", "b1", 0, None),
             ("join", "a2", 0, None), ("join", "b2", 0, None)]
    paths = extract_paths(_timeline(tasks, edges), CFG)
    assert set(paths.task_ids) == {"a0;a1;a2;join", "b0;b1;b2;join"} and len(paths) == 2


def test_an_edge_removed_before_its_dependent_finished_is_not_used():
    tasks, edges = _line(5)
    edges[2] = ("t3", "t2", 0, 3)  # removed on day 3, t3 finished on day 20
    assert extract_paths(_timeline(tasks, edges), CFG).empty  # both pieces are shorter than four


def test_a_task_that_never_finished_is_not_in_any_path_and_the_finished_part_ends_where_it_did():
    tasks, edges = _line(5)
    tasks["t4"] = (19, None)
    assert list(extract_paths(_timeline(tasks, edges), CFG).task_ids) == ["t0;t1;t2;t3"]


def test_a_dependency_that_finished_after_its_dependent_is_not_followed():
    tasks, edges = _line(5)
    tasks["t1"] = (4, 40)  # finishes after t2 and t3
    assert extract_paths(_timeline(tasks, edges), CFG).empty


def test_the_first_hop_is_the_roots_own_work_and_a_never_active_root_starts_when_it_ends():
    tasks, edges = _line(4)
    tasks["t0"] = (None, 5)
    row = extract_paths(_timeline(tasks, edges), CFG).iloc[0]
    assert row.end_to_end_days == 15 and row.max_hop_share == 5 / 15


def test_two_runs_give_the_same_paths_in_the_same_order():
    tl = _timeline(*_line(6))
    assert extract_paths(tl, CFG).equals(extract_paths(tl, CFG))
