import pandas as pd

from flow_health.detectors.config import SlowChainConfig, load_config
from flow_health.detectors.slow_chain import detect

CFG = SlowChainConfig()
COLUMNS = ["sink_task_id", "task_ids", "chain_length", "end_to_end_days", "max_hop_share"]


def _chain(name, length, days, share=0.3):
    return (f"{name}-sink", ";".join(f"{name}-{i}" for i in range(length)), length, days, share)


def _chains(*extra, length=5):
    """Twelve healthy chains of `length` tasks (median 10 days) plus the given (name, length, days, share) chains."""
    healthy = [_chain(f"h{i}", length, (9, 10, 11)[i % 3]) for i in range(12)]
    return pd.DataFrame(healthy + [_chain(*e) for e in extra], columns=COLUMNS)


def test_load_config_matches_the_dataclass_defaults():
    assert load_config().slow_chain == CFG


def test_a_chain_far_slower_than_its_length_group_with_spread_delay_is_flagged_with_the_right_severity():
    flags = detect(_chains(("low", 5, 38, 0.3), ("mid", 5, 45, 0.3), ("high", 5, 70, 0.3)), CFG)
    assert {f.entity_ids[0].rsplit("-", 1)[0]: f.severity for f in flags} == {"low": "low", "mid": "medium", "high": "high"}
    assert all(f.pattern == "SLOW_CHAIN" and f.entity_type == "chain" for f in flags)
    assert flags[0].entity_ids == sorted(flags[0].entity_ids, key=lambda t: int(t.rsplit("-", 1)[1]))  # root first


def test_decoys_are_not_flagged():
    flags = detect(_chains(
        ("one_link", 5, 70, 0.6),       # one link carries 60%: a chronic blocker, not a slow chain
        ("just_below", 5, 29, 0.3),     # 2.9 medians
        ("short", 3, 90, 0.3),          # shorter than the analysed range
        ("long_alone", 8, 120, 0.3),    # genuinely long, and no other chain of its length to compare with
    ), CFG)
    assert flags == []


def test_a_long_chain_is_not_flagged_for_being_long_when_its_length_group_is_equally_slow():
    long_ones = pd.DataFrame([_chain(f"l{i}", 8, (60, 65, 70)[i % 3]) for i in range(12)], columns=COLUMNS)
    assert detect(long_ones, CFG) == []


def test_explanation_contains_the_actual_numbers():
    (flag,) = detect(_chains(("slow", 5, 32, 0.3)), CFG)
    assert "זמן סגירה 32 יום" in flag.explanation and "פי 3.2" in flag.explanation
    assert "שרשראות באורך 5" in flag.explanation and "ליותר מ-50 אחוז" in flag.explanation
    assert flag.evidence["ratio_to_median"] == 3.2 and flag.evidence["chain_length"] == 5


def test_each_length_is_judged_only_against_its_own_group():
    mixed = pd.concat([_chains(("slow", 5, 40, 0.3), length=5),
                       pd.DataFrame([_chain(f"s{i}", 7, 40 + i % 3) for i in range(12)], columns=COLUMNS)])
    flags = detect(mixed.reset_index(drop=True), CFG)
    assert [f.evidence["chain_length"] for f in flags] == [5]


def test_a_group_where_most_chains_have_the_same_time_falls_back_to_the_iqr():
    days = [10.0] * 9 + [11.0, 12.0, 13.0]  # MAD = 0
    base = pd.DataFrame([_chain(f"h{i}", 5, d) for i, d in enumerate(days)], columns=COLUMNS)
    flags = detect(pd.concat([base, pd.DataFrame([_chain("slow", 5, 50)], columns=COLUMNS)], ignore_index=True), CFG)
    assert [f.entity_ids[0] for f in flags] == ["slow-0"]


def test_score_is_a_percentile_and_two_runs_give_the_same_output():
    chains = _chains(("a", 5, 40, 0.3), ("b", 5, 60, 0.3))
    first = detect(chains, CFG)
    assert {f.entity_ids[0]: f.score for f in first} == {"a-0": 0.5, "b-0": 1.0}
    assert first == detect(chains.iloc[::-1].reset_index(drop=True), CFG) == detect(chains, CFG)


def test_no_chains_no_flags():
    assert detect(pd.DataFrame(columns=COLUMNS), CFG) == []
