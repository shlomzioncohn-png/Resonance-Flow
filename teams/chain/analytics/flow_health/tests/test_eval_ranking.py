"""The ranked list: severity first, then score; flags of two detectors on one task point at each other."""
from flow_health.detectors.common import Flag
from flow_health.evaluation.ranking import COLUMNS, rank_flags, write_ranked


def flag(fid, pattern, entity_ids, severity, score):
    return Flag(flag_id=fid, pattern=pattern, entity_type="x", entity_ids=entity_ids, score=score,
                severity=severity, explanation=f"why {fid}")


def test_severity_comes_before_score():
    rows = rank_flags([flag("low-top", "CHRONIC_BLOCKER", ["a"], "low", 1.0),
                       flag("high-bottom", "CHRONIC_BLOCKER", ["b"], "high", 0.1),
                       flag("med", "CHRONIC_BLOCKER", ["c"], "medium", 0.9)])
    assert [r["flag_id"] for r in rows] == ["high-bottom", "med", "low-top"]
    assert [r["rank"] for r in rows] == [1, 2, 3]


def test_score_descending_within_a_severity_and_ties_are_stable():
    flags = [flag("b", "CHRONIC_BLOCKER", ["1"], "high", 0.5), flag("a", "CHRONIC_BLOCKER", ["2"], "high", 0.5),
             flag("c", "CHRONIC_BLOCKER", ["3"], "high", 0.9)]
    assert [r["flag_id"] for r in rank_flags(flags)] == ["c", "a", "b"]
    assert rank_flags(flags) == rank_flags(list(reversed(flags)))


def test_related_rows_point_at_each_other_and_stay_two_rows():
    rows = rank_flags([flag("chain", "SLOW_CHAIN", ["a", "b", "c", "d"], "high", 0.9),
                       flag("blocker", "CHRONIC_BLOCKER", ["c"], "low", 0.2),
                       flag("alone", "CHRONIC_BLOCKER", ["zzz"], "low", 0.1)])
    by_id = {r["flag_id"]: r for r in rows}
    assert len(rows) == 3
    assert by_id["chain"]["related"] == str(by_id["blocker"]["rank"])
    assert by_id["blocker"]["related"] == str(by_id["chain"]["rank"])
    assert by_id["alone"]["related"] == ""


def test_edge_flag_is_related_through_either_end():
    rows = rank_flags([flag("edge", "RECURRING_CYCLE", ["t>root"], "high", 0.5),
                       flag("root", "CHRONIC_BLOCKER", ["root"], "low", 0.5)])
    assert rows[0]["related"] == "2" and rows[1]["related"] == "1"


def test_two_flags_of_one_detector_are_not_related():
    rows = rank_flags([flag("x", "CHRONIC_BLOCKER", ["a"], "low", 0.1), flag("y", "CHRONIC_BLOCKER", ["a"], "low", 0.2)])
    assert [r["related"] for r in rows] == ["", ""]


def test_csv_has_the_required_columns_first(tmp_path):
    rows = rank_flags([flag("x", "CHRONIC_BLOCKER", ["a"], "low", 0.1)])
    write_ranked(rows, tmp_path / "ranked_flags.csv")
    header = (tmp_path / "ranked_flags.csv").read_text("utf-8").splitlines()[0].split(",")
    assert header[:6] == ["rank", "pattern", "entity", "severity", "score", "explanation"]
    assert header == COLUMNS
