"""TP / FP / FN counting, decoys, precision and recall, including division by zero."""
from flow_health.detectors.common import Flag
from flow_health.evaluation.metrics import Counts, evaluate
from flow_health.evaluation.truth import Disease, Truth


def flag(fid, pattern, entity_ids):
    return Flag(flag_id=fid, pattern=pattern, entity_type="x", entity_ids=entity_ids, score=0.5, severity="low",
                explanation="")


def chronic(id_, root, severity="moderate", in_stage_a=True, type_="chronic_blocking"):
    return Disease(id=id_, type=type_, severity=severity, in_stage_a=in_stage_a, root_task_id=root,
                   task_ids=frozenset({root}), edges=frozenset())


def truth(diseases, decoys=()):
    return Truth(tuple(diseases), frozenset(decoys))


def test_two_flags_on_one_disease_count_one_tp():
    t = truth([chronic("d1", "a")])
    r = evaluate([flag("f1", "CHRONIC_BLOCKER", ["a"]), flag("f2", "CHRONIC_BLOCKER", ["a"])], t)
    assert r["overall"]["tp"] == 1 and r["overall"]["fp"] == 0 and r["overall"]["fn"] == 0


def test_flag_on_a_decoy_is_fp_and_reported_as_fallen():
    t = truth([chronic("d1", "a")], decoys=["x"])
    r = evaluate([flag("f1", "CHRONIC_BLOCKER", ["x"])], t)
    assert r["overall"]["fp"] == 1 and r["fell_for_decoy"] == ["f1"]
    assert r["flag_outcomes"]["f1"] == "FP"
    assert r["overall"]["fn"] == 1 and r["missed_diseases"] == ["d1"]


def test_plain_fp_is_not_a_decoy_hit():
    r = evaluate([flag("f1", "CHRONIC_BLOCKER", ["y"])], truth([chronic("d1", "a")], decoys=["x"]))
    assert r["overall"]["fp"] == 1 and r["fell_for_decoy"] == []


def test_edge_flag_touching_a_decoy_task_falls_for_it():
    r = evaluate([flag("f1", "RECURRING_CYCLE", ["p>x"])], truth([], decoys=["x"]))
    assert r["fell_for_decoy"] == ["f1"]


def test_flag_on_a_disease_outside_stage_a_is_neither_tp_nor_fp():
    t = truth([chronic("g1", "a", in_stage_a=False, type_="growing_bottleneck")])
    r = evaluate([flag("f1", "CHRONIC_BLOCKER", ["a"])], t)
    assert r["overall"]["tp"] == r["overall"]["fp"] == r["overall"]["fn"] == 0
    assert r["out_of_scope_flags"] == ["f1"]


def test_flag_of_the_wrong_pattern_does_not_hit_a_disease():
    t = truth([chronic("d1", "a")])
    r = evaluate([flag("f1", "SLOW_CHAIN", ["a"])], t)
    assert r["overall"]["tp"] == 0 and r["overall"]["fp"] == 1 and r["overall"]["fn"] == 1


def test_precision_and_recall():
    t = truth([chronic("d1", "a"), chronic("d2", "b"), chronic("d3", "c"), chronic("d4", "d")])
    flags = [flag("f1", "CHRONIC_BLOCKER", ["a"]), flag("f2", "CHRONIC_BLOCKER", ["b"]),
             flag("f3", "CHRONIC_BLOCKER", ["z"])]
    c = evaluate(flags, t)["per_detector"]["CHRONIC_BLOCKER"]
    assert (c["tp"], c["fp"], c["fn"]) == (2, 1, 2)
    assert c["precision"] == 2 / 3 and c["recall"] == 0.5


def test_no_flags_gives_undefined_precision_and_zero_recall():
    r = evaluate([], truth([chronic("d1", "a")]))
    assert r["overall"]["precision"] is None and r["overall"]["recall"] == 0.0
    assert r["per_detector"]["SLOW_CHAIN"]["recall"] is None       # nothing planted for it either


def test_counts_with_nothing_at_all_never_divide_by_zero():
    c = Counts()
    assert c.precision is None and c.recall is None


def test_low_severity_recall_is_separate():
    t = truth([chronic("d1", "a", severity="subtle"), chronic("d2", "b", severity="subtle"),
               chronic("d3", "c", severity="severe")])
    r = evaluate([flag("f1", "CHRONIC_BLOCKER", ["a"]), flag("f3", "CHRONIC_BLOCKER", ["c"])], t)
    assert r["recall_low_severity"]["overall"] == {"planted": 2, "found": 1, "recall": 0.5}
    assert r["recall_by_severity"]["severe"]["recall"] == 1.0
