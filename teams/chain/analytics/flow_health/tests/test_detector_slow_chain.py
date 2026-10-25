import pandas as pd

from flow_health.detectors.config import SlowChainConfig, load_config
from flow_health.detectors.slow_chain import detect

CFG = SlowChainConfig()
PATH_COLUMNS = ["sink_task_id", "task_ids", "chain_length", "end_to_end_days", "max_hop_share"]
HEALTHY_LINK_RATIOS = (0.8, 1.0, 1.2)


def _chain(name, length, link_ratio, share=0.3, days=30.0):
    """One path row plus the task rows of its links, each with the same work-time ratio."""
    ids = [f"{name}-{i}" for i in range(length)]
    path = (ids[-1], ";".join(ids), length, days, share)
    return path, [(task, link_ratio) for task in ids]


def _frames(*chains, length=5):
    """Twelve healthy paths of `length` tasks (links 0.8 / 1.0 / 1.2, so the group's median value is 1.0) plus the given
    (name, length, link_ratio[, share]) chains."""
    built = [_chain(f"h{i}", length, HEALTHY_LINK_RATIOS[i % 3]) for i in range(12)]
    built += [_chain(*c) for c in chains]
    paths = pd.DataFrame([p for p, _ in built], columns=PATH_COLUMNS)
    tasks = pd.DataFrame([row for _, rows in built for row in rows], columns=["task_id", "active_ratio_to_tier_project"])
    return paths, tasks


def _ids(flags):
    return {f.entity_ids[0].rsplit("-", 1)[0]: f for f in flags}


def test_load_config_matches_the_dataclass_defaults():
    assert load_config().slow_chain == CFG


def test_a_path_whose_links_are_all_far_slower_than_usual_is_flagged_with_the_right_severity():
    flags = detect(*_frames(("low", 5, 3.2), ("mid", 5, 5.0), ("high", 5, 9.0)), CFG)
    assert {k: f.severity for k, f in _ids(flags).items()} == {"low": "low", "mid": "medium", "high": "high"}
    assert all(f.pattern == "SLOW_CHAIN" and f.entity_type == "chain" for f in flags)
    assert flags[0].entity_ids[0].endswith("-0")  # root first


def test_decoys_are_not_flagged():
    flags = detect(*_frames(
        ("one_link", 5, 5.0, 0.6),   # one link carries 60% of the time: a chronic blocker, not a slow chain
        ("just_below", 5, 1.9),      # 1.9 group medians
        ("short", 3, 9.0),           # shorter than the analysed range
        ("long_alone", 8, 9.0),      # no other path of its length to compare with
    ), CFG)
    assert flags == []


def test_a_group_where_every_path_is_slow_flags_none_because_length_alone_is_not_the_signal():
    paths, tasks = _frames(length=8)
    slow = [_chain(f"s{i}", 8, 5.0 + (i % 3) * 0.2) for i in range(12)]
    paths = pd.DataFrame([p for p, _ in slow], columns=PATH_COLUMNS)
    tasks = pd.DataFrame([row for _, rows in slow for row in rows], columns=["task_id", "active_ratio_to_tier_project"])
    assert detect(paths, tasks, CFG) == []


def test_explanation_contains_the_actual_numbers():
    (flag,) = detect(*_frames(("slow", 5, 3.2, 0.31, 38.0)), CFG)
    assert "שרשרת באורך 5" in flag.explanation and "ב-38 יום" in flag.explanation
    assert "פי 3.2" in flag.explanation and "ליותר מ-50 אחוז" in flag.explanation and "הגדולה ביותר: 31 אחוז" in flag.explanation
    assert flag.evidence["median_link_ratio"] == 3.2 and flag.evidence["chain_length"] == 5


def test_each_length_is_judged_only_against_its_own_group():
    p5, t5 = _frames(("slow", 5, 4.0), length=5)
    p7, t7 = _frames(length=7)
    p7["task_ids"] = p7["task_ids"].str.replace("h", "g")
    t7["task_id"] = t7["task_id"].str.replace("h", "g")
    flags = detect(pd.concat([p5, p7], ignore_index=True), pd.concat([t5, t7], ignore_index=True), CFG)
    assert [f.evidence["chain_length"] for f in flags] == [5]


def test_overlapping_windows_of_one_slow_stretch_give_one_flag_the_strongest():
    paths, tasks = _frames()
    slow_ids = [f"x-{i}" for i in range(6)]
    extra = pd.DataFrame([("x-4", ";".join(slow_ids[:5]), 5, 30.0, 0.3), ("x-5", ";".join(slow_ids[1:6]), 5, 30.0, 0.3)],
                         columns=PATH_COLUMNS)
    ratios = pd.DataFrame({"task_id": slow_ids, "active_ratio_to_tier_project": [6.0, 6.0, 6.0, 6.0, 6.0, 4.0]})
    flags = detect(pd.concat([paths, extra], ignore_index=True), pd.concat([tasks, ratios], ignore_index=True), CFG)
    assert len(flags) == 1 and flags[0].entity_ids == slow_ids[:5]


def test_a_task_with_an_unknown_ratio_does_not_break_the_median():
    paths, tasks = _frames(("slow", 5, 5.0))
    tasks.loc[tasks["task_id"] == "slow-2", "active_ratio_to_tier_project"] = float("nan")
    assert len(detect(paths, tasks, CFG)) == 1


def test_a_group_where_most_paths_have_the_same_value_falls_back_to_the_iqr():
    healthy = [_chain(f"h{i}", 5, r) for i, r in enumerate([1.0] * 9 + [1.2, 1.4, 1.6])]
    slow = _chain("slow", 5, 6.0)
    built = healthy + [slow]
    paths = pd.DataFrame([p for p, _ in built], columns=PATH_COLUMNS)
    tasks = pd.DataFrame([row for _, rows in built for row in rows], columns=["task_id", "active_ratio_to_tier_project"])
    assert [f.entity_ids[0] for f in detect(paths, tasks, CFG)] == ["slow-0"]


def test_score_is_a_percentile_and_two_runs_give_the_same_output():
    paths, tasks = _frames(("a", 5, 4.0), ("b", 5, 8.0))
    first = detect(paths, tasks, CFG)
    assert {k: f.score for k, f in _ids(first).items()} == {"a": 0.5, "b": 1.0}
    assert first == detect(paths.iloc[::-1].reset_index(drop=True), tasks, CFG) == detect(paths, tasks, CFG)


def test_no_paths_no_flags():
    assert detect(pd.DataFrame(columns=PATH_COLUMNS), pd.DataFrame(columns=["task_id", "active_ratio_to_tier_project"]), CFG) == []
