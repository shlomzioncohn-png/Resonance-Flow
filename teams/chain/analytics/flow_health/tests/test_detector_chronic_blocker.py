import pandas as pd

from flow_health.detectors.chronic_blocker import detect
from flow_health.detectors.config import ChronicBlockerConfig, load_config

CFG = ChronicBlockerConfig()
COLUMNS = ["task_id", "tier", "is_open", "days_active", "active_ratio_to_tier_project", "days_blocked_total",
           "n_dependents_max"]


def _tasks(*rows):
    """Row: (task_id, tier, is_open, active_days, dependents). The usual work time is 10 days, so 85 days is a ratio
    of 8.5."""
    data = [(task, tier, is_open, days, days / 10, 0.0, deps) for task, tier, is_open, days, deps in rows]
    return pd.DataFrame(data, columns=COLUMNS)


def _by_id(flags):
    return {f.entity_ids[0]: f for f in flags}


def test_load_config_matches_the_dataclass_defaults():
    assert load_config().chronic_blocker == CFG


def test_worked_on_far_beyond_the_usual_with_a_dependent_is_flagged_with_the_right_severity():
    flags = detect(_tasks(("low", "tactical", True, 85, 1), ("mid", "tactical", True, 170, 2),
                          ("high", "tactical", False, 350, 8)), CFG)
    assert {i: f.severity for i, f in _by_id(flags).items()} == {"low": "low", "mid": "medium", "high": "high"}
    assert all(f.pattern == "CHRONIC_BLOCKER" and f.entity_type == "task" for f in flags)


def test_severity_boundaries_belong_to_the_higher_band():
    flags = detect(_tasks(("a", "tactical", True, 80, 2), ("b", "tactical", True, 160, 2),
                          ("c", "tactical", True, 320, 2)), CFG)
    assert {i: f.severity for i, f in _by_id(flags).items()} == {"a": "low", "b": "medium", "c": "high"}


def test_decoys_are_not_flagged():
    flags = detect(_tasks(
        ("long_leaf", "tactical", True, 400, 0),    # very long, nobody waits on it
        ("just_below", "tactical", True, 79, 6),    # 7.9 usual work times
        ("normal", "tactical", False, 10, 6),       # a busy hub that is worked on as long as usual
        ("busy", "tactical", False, 30, 9),         # three times the usual, with many dependents: not enough on its own
    ), CFG)
    assert flags == []


def test_a_task_that_was_never_blocked_can_still_be_a_chronic_blocker():
    (flag,) = detect(_tasks(("held", "tactical", False, 90, 3)), CFG)
    assert flag.evidence["days_blocked"] == 0 and flag.severity == "low"


def test_explanation_contains_the_actual_numbers():
    (flag,) = detect(_tasks(("t", "tactical", True, 85, 5)), CFG)
    assert "Worked on for 85 days" in flag.explanation and "8.5x" in flag.explanation
    assert "tactical tasks in the same project" in flag.explanation and "(10 days)" in flag.explanation
    assert "blocks 5 tasks" in flag.explanation and "still not finished" in flag.explanation
    assert flag.evidence["ratio_to_tier_median"] == 8.5 and flag.evidence["n_dependents_max"] == 5


def test_a_finished_task_is_explained_without_the_open_note():
    (flag,) = detect(_tasks(("t", "tactical", False, 85, 5)), CFG)
    assert "still not finished" not in flag.explanation


def test_unknown_tier_is_compared_with_all_tasks_in_the_wording():
    (flag,) = detect(_tasks(("t", "UNKNOWN", True, 85, 5)), CFG)
    assert "all tasks" in flag.explanation and flag.evidence["tier"] == "UNKNOWN"


def test_score_is_a_percentile_within_the_detector_and_the_strongest_gets_one():
    flags = detect(_tasks(("a", "tactical", True, 85, 5), ("b", "tactical", True, 100, 5),
                          ("c", "tactical", True, 200, 5)), CFG)
    assert {i: f.score for i, f in _by_id(flags).items()} == {"a": round(1 / 3, 4), "b": round(2 / 3, 4), "c": 1.0}


def test_two_runs_give_identical_output_even_when_the_row_order_changes():
    rows = [("a", "tactical", True, 85, 5), ("b", "tactical", True, 100, 4)]
    first = detect(_tasks(*rows), CFG)
    assert first == detect(_tasks(*reversed(rows)), CFG) == detect(_tasks(*rows), CFG)


def test_no_tasks_no_flags():
    assert detect(pd.DataFrame(columns=COLUMNS), CFG) == []
