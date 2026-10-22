import pandas as pd

from flow_health.detectors.chronic_blocker import detect
from flow_health.detectors.config import ChronicBlockerConfig, load_config

CFG = ChronicBlockerConfig()
COLUMNS = ["task_id", "tier", "is_open", "age_ratio_to_tier", "days_blocked_total", "n_dependents_max"]


def _tasks(*rows):
    """Row: (task_id, tier, is_open, age_ratio, blocked_days, dependents). Median blocked days of every tier is 10
    thanks to the filler tasks, so a blocked time of 31 is 3.1 medians."""
    filler = [(f"f-{tier}-{i}", tier, False, 1.0, 10.0, 0)
              for tier in ("tactical", "operational") for i in range(10)]
    return pd.DataFrame(list(rows) + filler, columns=COLUMNS)


def _flag(flags, task_id):
    return next((f for f in flags if f.entity_ids == [task_id]), None)


def test_load_config_matches_the_dataclass_defaults():
    assert load_config().chronic_blocker == CFG


def test_blocked_far_beyond_the_tier_with_dependents_is_flagged_with_the_right_severity():
    flags = detect(_tasks(("low", "tactical", True, 4.0, 31.0, 5),
                          ("mid", "tactical", True, 4.0, 45.0, 3),
                          ("high", "tactical", False, 9.0, 70.0, 8)), CFG)
    assert {f.entity_ids[0]: f.severity for f in flags} == {"low": "low", "mid": "medium", "high": "high"}
    assert all(f.pattern == "CHRONIC_BLOCKER" and f.entity_type == "task" for f in flags)


def test_severity_boundaries_belong_to_the_higher_band():
    flags = detect(_tasks(("a", "tactical", True, 4.0, 30.0, 3), ("b", "tactical", True, 4.0, 40.0, 3),
                          ("c", "tactical", True, 4.0, 60.0, 3)), CFG)
    assert {f.entity_ids[0]: f.severity for f in flags} == {"a": "low", "b": "medium", "c": "high"}


def test_decoys_are_not_flagged():
    flags = detect(_tasks(
        ("long_leaf", "tactical", True, 9.0, 90.0, 0),         # very long, nobody waits on it
        ("two_waiting", "tactical", True, 9.0, 90.0, 2),       # one dependent short of the bar
        ("just_below", "tactical", True, 4.0, 29.0, 6),        # 2.9 medians
        ("finished_on_time", "tactical", False, 1.5, 90.0, 6),  # finished, not late: not a candidate
    ), CFG)
    assert flags == []


def test_explanation_contains_the_actual_numbers():
    (flag,) = detect(_tasks(("t", "tactical", True, 4.0, 31.0, 5)), CFG)
    assert "חסומה 31 יום" in flag.explanation and "פי 3.1" in flag.explanation
    assert "משימות טקטיות" in flag.explanation and "חוסמת 5 משימות" in flag.explanation
    assert "עדיין לא הסתיימה" in flag.explanation
    assert flag.evidence["ratio_to_tier_median"] == 3.1 and flag.evidence["n_dependents_max"] == 5


def test_a_finished_task_is_explained_without_the_open_note():
    (flag,) = detect(_tasks(("t", "tactical", False, 4.0, 31.0, 5)), CFG)
    assert "עדיין לא הסתיימה" not in flag.explanation


def test_unknown_tier_is_compared_with_all_tasks():
    (flag,) = detect(_tasks(("t", "UNKNOWN", True, 4.0, 31.0, 5)), CFG)
    assert "כלל המשימות" in flag.explanation and flag.evidence["tier"] == "UNKNOWN"


def test_score_is_a_percentile_within_the_detector_and_the_strongest_gets_one():
    flags = detect(_tasks(("a", "tactical", True, 4.0, 31.0, 5), ("b", "tactical", True, 4.0, 50.0, 5),
                          ("c", "tactical", True, 4.0, 80.0, 5)), CFG)
    assert {f.entity_ids[0]: f.score for f in flags} == {"a": round(1 / 3, 4), "b": round(2 / 3, 4), "c": 1.0}


def test_two_runs_give_identical_output_even_when_the_row_order_changes():
    rows = [("a", "tactical", True, 4.0, 31.0, 5), ("b", "tactical", True, 4.0, 50.0, 4)]
    first = detect(_tasks(*rows), CFG)
    second = detect(_tasks(*reversed(rows)), CFG)
    assert first == second and first == detect(_tasks(*rows), CFG)


def test_no_tasks_no_flags():
    assert detect(pd.DataFrame(columns=COLUMNS), CFG) == []
