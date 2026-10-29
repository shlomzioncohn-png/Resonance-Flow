"""The mentor sample: deterministic, 5 per detector, never overwrites an answered sheet."""
import csv

from flow_health.detectors.common import PATTERNS, Flag
from flow_health.evaluation.mentor import COLUMNS, sample_flags, summarize, write_sample


def many(n_per_pattern):
    return [Flag(flag_id=f"{p}:{i:03d}", pattern=p, entity_type="x", entity_ids=[f"{p}{i}"], score=0.5,
                 severity="low", explanation=f"why {p} {i}") for p in PATTERNS for i in range(n_per_pattern)]


def test_five_per_detector():
    s = sample_flags(many(12))
    assert len(s) == 15
    assert {p: sum(f.pattern == p for f in s) for p in PATTERNS} == {p: 5 for p in PATTERNS}


def test_deterministic_and_independent_of_input_order():
    flags = many(12)
    assert [f.flag_id for f in sample_flags(flags)] == [f.flag_id for f in sample_flags(list(reversed(flags)))]
    assert sample_flags(flags) == sample_flags(flags)


def test_another_seed_gives_another_sample():
    flags = many(30)
    assert sample_flags(flags, seed=1) != sample_flags(flags, seed=2)


def test_a_detector_with_fewer_than_five_gives_all_of_them():
    flags = [f for f in many(12) if f.pattern != "RECURRING_CYCLE" or f.flag_id.endswith(("000", "001"))]
    s = sample_flags(flags)
    assert sum(f.pattern == "RECURRING_CYCLE" for f in s) == 2 and len(s) == 12


def test_sheet_columns_and_blank_answers(tmp_path):
    p = tmp_path / "mentor_review.csv"
    assert write_sample(many(12), p)
    with p.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    assert list(rows[0]) == COLUMNS and len(rows) == 15
    assert all(r["reasonable"] == "" and r["comment"] == "" for r in rows)
    assert summarize(p) is None


def test_answered_sheet_is_not_overwritten_and_is_summarised(tmp_path):
    p = tmp_path / "mentor_review.csv"
    write_sample(many(12), p)
    with p.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    rows[0]["reasonable"], rows[0]["comment"] = "yes", "clear"
    rows[1]["reasonable"], rows[1]["comment"] = "no", "too weak"
    with p.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    assert not write_sample(many(12), p)
    s = summarize(p)
    assert (s["answered"], s["total"], s["reasonable"]) == (2, 15, 1)
    assert [c["comment"] for c in s["comments"]] == ["clear", "too weak"]
