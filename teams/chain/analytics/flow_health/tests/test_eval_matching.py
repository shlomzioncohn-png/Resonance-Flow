"""The matching rule between a flag and a planted disease, on tiny hand-built examples."""
import pytest

from flow_health.detectors.common import Flag
from flow_health.evaluation.matching import flag_tasks, jaccard, matches
from flow_health.evaluation.truth import Disease


def flag(pattern, entity_ids, flag_id="f"):
    return Flag(flag_id=flag_id, pattern=pattern, entity_type="x", entity_ids=entity_ids, score=0.5,
                severity="low", explanation="")


def disease(type_, root="a", tasks=("a",), edges=(), severity="moderate", in_stage_a=True, id_="d1"):
    return Disease(id=id_, type=type_, severity=severity, in_stage_a=in_stage_a, root_task_id=root,
                   task_ids=frozenset(tasks), edges=frozenset(edges))


def test_chronic_blocker_needs_the_same_task():
    d = disease("chronic_blocking", root="a")
    assert matches(flag("CHRONIC_BLOCKER", ["a"]), d)
    assert not matches(flag("CHRONIC_BLOCKER", ["b"]), d)


def test_recurring_cycle_needs_the_same_edge():
    d = disease("recurring_blocker", edges=["b>a"])
    assert matches(flag("RECURRING_CYCLE", ["b>a"]), d)
    assert not matches(flag("RECURRING_CYCLE", ["c>a"]), d)


def test_near_cycle_pair_matches_when_either_edge_does():
    d = disease("recurring_blocker", edges=["b>a"])
    assert matches(flag("RECURRING_CYCLE", ["b>a", "b>z"]), d)
    assert matches(flag("RECURRING_CYCLE", ["b>z", "b>a"]), d)
    assert not matches(flag("RECURRING_CYCLE", ["b>z", "b>y"]), d)


def test_slow_chain_matches_exactly_at_the_jaccard_threshold():
    d = disease("slow_chain", tasks=["a", "b", "c", "d"])
    at = flag("SLOW_CHAIN", ["a", "b", "x", "y"])          # intersection 2, union 6 -> 0.33, below
    assert not matches(at, d)
    exact = flag("SLOW_CHAIN", ["a", "b", "c"])            # 3 / 4 = 0.75
    assert matches(exact, d)
    on_threshold = flag("SLOW_CHAIN", ["a", "b"])          # 2 / 4 = 0.5 exactly
    assert jaccard({"a", "b"}, {"a", "b", "c", "d"}) == 0.5
    assert matches(on_threshold, d)


def test_slow_chain_just_below_the_threshold_does_not_match():
    d = disease("slow_chain", tasks=["a", "b", "c", "d", "e"])
    below = flag("SLOW_CHAIN", ["a", "b", "x", "y"])       # 2 / 7
    barely = flag("SLOW_CHAIN", ["a", "b", "c", "x", "y", "z"])   # 3 / 8 = 0.375
    assert not matches(below, d) and not matches(barely, d)


def test_jaccard_of_empty_sets_is_zero():
    assert jaccard(set(), set()) == 0.0


def test_unknown_pattern_is_an_error():
    with pytest.raises(ValueError):
        matches(flag("OTHER", ["a"]), disease("slow_chain"))


def test_flag_tasks_splits_edges_into_both_ends():
    assert flag_tasks(flag("RECURRING_CYCLE", ["b>a", "b>z"])) == {"a", "b", "z"}
    assert flag_tasks(flag("SLOW_CHAIN", ["a", "b"])) == {"a", "b"}
