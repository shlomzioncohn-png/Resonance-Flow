from datetime import datetime, timedelta, timezone

import pandas as pd

from flow_health.detectors.config import RecurringCycleConfig, load_config
from flow_health.detectors.recurring_cycle import detect

CFG = RecurringCycleConfig()
T0 = datetime(2026, 3, 1, 9, tzinfo=timezone.utc)


def _history(*events):
    """Event: (task, depends_on, action, day_offset)."""
    rows = [(t, d, a, T0 + timedelta(days=day), i) for i, (t, d, a, day) in enumerate(events)]
    return pd.DataFrame(rows, columns=["task_id", "depends_on_task_id", "action", "created_at", "sequence"])


def _flapping(task, target, days):
    """ADDED at days[0], then alternating REMOVED / ADDED at the following days."""
    return [(task, target, "ADDED" if i % 2 == 0 else "REMOVED", d) for i, d in enumerate(days)]


def test_load_config_matches_the_dataclass_defaults():
    assert load_config().recurring_cycle == CFG


def test_an_edge_that_switched_four_times_in_27_days_is_flagged_low():
    (flag,) = detect(_history(*_flapping("A", "B", [0, 7, 14, 21, 27])), CFG)
    assert (flag.pattern, flag.entity_type, flag.entity_ids, flag.severity) == (
        "RECURRING_CYCLE", "edge", ["A>B"], "low")
    assert flag.evidence["transitions"] == 4 and flag.evidence["median_gap_days"] == 7


def test_severity_follows_the_number_of_switches_and_how_even_the_gaps_are():
    medium = detect(_history(*_flapping("A", "B", [0, 3, 6, 9, 12, 15])), CFG)       # 5 switches, even gaps -> high
    assert medium[0].severity == "high"
    uneven = detect(_history(*_flapping("A", "B", [0, 1.2, 2, 8, 9, 10])), CFG)      # 5 switches, uneven gaps
    assert uneven[0].severity == "medium"
    many = detect(_history(*_flapping("A", "B", [0, 2, 4, 6, 8, 10, 12, 14, 16])), CFG)  # 8 switches
    assert many[0].severity == "high"


def test_decoys_are_not_flagged():
    flags = detect(_history(
        ("F", "G", "ADDED", 0), ("F", "G", "REMOVED", 5), ("F", "G", "ADDED", 9),       # fixed once and re-added: 2 switches
        ("H", "I", "ADDED", 0), ("H", "I", "REMOVED", 30),                               # a single fix
        *_flapping("J", "K", [0, 40, 80, 120]),                                          # slow: median gap 40 days
        *_flapping("L", "M", [0, 0.2, 0.4, 0.6]),                                        # automation noise: hours apart
    ), CFG)
    assert flags == []


def test_explanation_contains_the_actual_numbers():
    (flag,) = detect(_history(*_flapping("A", "B", [0, 7, 14, 21, 27])), CFG)
    assert "4 פעמים" in flag.explanation and "27 יום" in flag.explanation and "מרווח חציוני 7 ימים" in flag.explanation


def test_near_cycle_pair_is_flagged_on_both_edges():
    (flag,) = detect(_history(
        ("T", "X", "ADDED", 0), ("T", "Y", "ADDED", 0),
        ("T", "X", "REMOVED", 5), ("T", "X", "ADDED", 12)), CFG)
    assert flag.entity_type == "edge_pair" and flag.entity_ids == ["T>X", "T>Y"] and flag.severity == "low"
    assert flag.evidence["days_away"] == 7 and "7 ימים" in flag.explanation


def test_pair_decoys_a_lone_edge_and_a_sibling_that_was_not_live():
    assert detect(_history(("T", "X", "ADDED", 0), ("T", "X", "REMOVED", 5), ("T", "X", "ADDED", 12)), CFG) == []
    assert detect(_history(("T", "X", "ADDED", 0), ("T", "Y", "ADDED", 6),
                           ("T", "X", "REMOVED", 5), ("T", "X", "ADDED", 12)), CFG) == []


def test_score_is_a_percentile_and_two_runs_give_the_same_output():
    events = _flapping("A", "B", [0, 3, 6, 9, 12]) + _flapping("C", "D", [0, 3, 6, 9, 12, 15, 18, 21, 24])
    first = detect(_history(*events), CFG)
    assert {f.entity_ids[0]: f.score for f in first} == {"A>B": 0.5, "C>D": 1.0}
    assert first == detect(_history(*reversed(events)).sort_values("sequence"), CFG)
    assert first == detect(_history(*events), CFG)


def test_empty_history_no_flags():
    assert detect(_history(), CFG) == []


def test_events_after_the_blocker_finished_do_not_count():
    events = _history(*_flapping("A", "B", [0, 3, 6, 9, 12]))
    assert len(detect(events, CFG, {"B": T0 + timedelta(days=13)})) == 1  # B finished after the last switch
    assert detect(events, CFG, {"B": T0 + timedelta(days=4)}) == []       # B finished early: only 1 switch happened while it was open
    assert len(detect(events, CFG, {})) == 1                              # nothing known to be finished


def test_a_near_cycle_on_a_blocker_that_had_already_finished_is_not_flagged():
    events = _history(("T", "X", "ADDED", 0), ("T", "Y", "ADDED", 0), ("T", "X", "REMOVED", 5), ("T", "X", "ADDED", 12))
    assert len(detect(events, CFG, {"X": T0 + timedelta(days=20)})) == 1
    assert detect(events, CFG, {"X": T0 + timedelta(days=8)}) == []  # X finished before it came back


def test_a_flapping_edge_counts_only_when_its_blocker_is_itself_slow():
    events = _history(*_flapping("A", "B", [0, 3, 6, 9, 12]))
    assert len(detect(events, CFG, None, {"B": 4.0})) == 1       # the bar is inclusive
    assert detect(events, CFG, None, {"B": 3.9}) == []           # a healthy task that was re-planned
    assert detect(events, CFG, None, {}) == []                   # an unknown blocker does not qualify
    assert len(detect(events, CFG, None, None)) == 1             # no ratios given: no such condition


def test_a_near_cycle_also_needs_a_slow_blocker():
    events = _history(("T", "X", "ADDED", 0), ("T", "Y", "ADDED", 0), ("T", "X", "REMOVED", 5), ("T", "X", "ADDED", 12))
    assert len(detect(events, CFG, None, {"X": 9.0})) == 1
    assert detect(events, CFG, None, {"X": 1.0}) == []
