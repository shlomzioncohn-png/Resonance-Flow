"""Deadline and effort estimate on the history rows, and the severity score built on them."""
from __future__ import annotations

import csv
import json
import statistics
from dataclasses import replace
from pathlib import Path

import pytest

from flow_health.generator import GeneratorConfig, generate, validate
from flow_health.generator import severity as sev
from flow_health.generator.export import write_dataset

FULL = replace(GeneratorConfig(), excluded_diseases=())
DOC = Path(__file__).resolve().parents[1] / "docs" / "severity.md"


@pytest.fixture(scope="module")
def full():
    return generate(FULL)


@pytest.fixture(scope="module")
def truth(full, tmp_path_factory):
    out = tmp_path_factory.mktemp("truth")
    write_dataset(out, full.config, full.world, full.history)
    return out, json.loads((out / "ground_truth.json").read_text(encoding="utf-8"))["diseases"]


# ---- deadline and estimate ----

def test_history_rows_carry_deadline_and_estimate_constant_per_task(full):
    per_task = {}
    for r in full.history.state_rows:
        assert per_task.setdefault(r.task_id, (r.deadline, r.effort_estimate_minutes)) == (r.deadline, r.effort_estimate_minutes)
    assert validate(full.history.dependency_rows, full.history.state_rows) == []


def test_shares_with_a_deadline_and_an_estimate_follow_inbox(full):
    tasks = full.world.tasks
    assert abs(sum(t.deadline is not None for t in tasks) / len(tasks) - 0.70) < 0.03
    assert abs(sum(t.effort_estimate_minutes is not None for t in tasks) / len(tasks) - 0.60) < 0.03


def test_estimates_are_positive_quarter_hours(full):
    estimates = [t.effort_estimate_minutes for t in full.world.tasks if t.effort_estimate_minutes is not None]
    assert min(estimates) >= 15 and all(e % 15 == 0 for e in estimates)


def test_deadlines_come_after_creation_and_some_are_missed(full):
    with_deadline = [t for t in full.world.tasks if t.deadline]
    assert all(t.deadline > t.created_at for t in with_deadline)
    done = {r.task_id: r.created_at for r in full.history.state_rows if r.to_status == "DONE"}
    missed = sum(1 for t in with_deadline if t.id in done and done[t.id] > t.deadline)
    assert 0.03 < missed / len(with_deadline) < 0.5  # real teams miss some, not all


def test_a_deliberately_slow_chain_overruns_its_estimates(full):
    started, took = {}, {}
    for r in full.history.state_rows:
        if r.to_status == "ACTIVE" and r.task_id not in started:
            started[r.task_id] = r.created_at
        elif r.to_status == "DONE" and r.task_id in started:
            took[r.task_id] = (r.created_at - started[r.task_id]).total_seconds() / 60
    chain_ids = {t for d in full.world.diseases if d.type == "slow_chain" and d.severity == "severe" for t in d.task_ids}
    ratios = [took[t.id] / t.effort_estimate_minutes for t in full.world.tasks
              if t.id in chain_ids and t.id in took and t.effort_estimate_minutes]
    assert statistics.median(ratios) > 3


def test_loader_reads_the_two_columns_and_survives_files_without_them(truth, tmp_path):
    pytest.importorskip("pandas")
    from flow_health.loaders import CsvLoader

    out, _ = truth
    states = CsvLoader(out).load().task_state_history
    assert str(states["deadline"].dt.tz) == "UTC" and str(states["effort_estimate_minutes"].dtype) == "Int64"

    for name in ("tasks", "task_meta", "dependency_history"):
        (tmp_path / f"{name}.csv").write_text((out / f"{name}.csv").read_text(encoding="utf-8"), encoding="utf-8")
    with (out / "task_state_history.csv").open(encoding="utf-8") as src, \
            (tmp_path / "task_state_history.csv").open("w", newline="", encoding="utf-8") as dst:
        rows = list(csv.reader(src))
        csv.writer(dst, lineterminator="\n").writerows([r[:9] for r in rows])  # drop the two extra columns
    old = CsvLoader(tmp_path).load().task_state_history
    assert old["deadline"].isna().all() and old["effort_estimate_minutes"].isna().all()


def test_validator_flags_a_deadline_that_changes_between_rows(full):
    rows = list(full.history.state_rows)
    task = rows[0].task_id
    i = next(i for i in range(1, len(rows)) if rows[i].task_id == task)
    rows[i] = replace(rows[i], effort_estimate_minutes=(rows[i].effort_estimate_minutes or 0) + 15)
    assert validate(full.history.dependency_rows, rows)


# ---- the severity score ----

def test_every_disease_has_a_score_a_level_and_its_basis(truth):
    out, diseases = truth
    for d in diseases:
        o = d["observed"]
        assert 0 <= o["severity_score"] <= 100
        assert o["severity_level"] == sev.level_of(o["severity_score"])
        assert set(o["severity_basis"]) >= {"abnormality", "overrun", "deadline", "scale"}
    with (out / "ground_truth.csv").open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert all(r["severity_score"] and r["severity_level"] for r in rows)
    assert "severity" in rows[0]  # the planted strength is kept next to it


def test_planted_strength_and_actual_severity_are_related_but_not_the_same(truth):
    _, diseases = truth
    stage_a = [d for d in diseases if d["in_stage_a"]]
    mean = lambda level: statistics.mean(d["observed"]["severity_score"] for d in stage_a if d["severity"] == level)
    assert mean("subtle") < mean("moderate") < mean("severe")
    subtle = [d["observed"]["severity_score"] for d in stage_a if d["severity"] == "subtle"]
    severe = [d["observed"]["severity_score"] for d in stage_a if d["severity"] == "severe"]
    assert max(subtle) > min(severe)  # a subtly planted case can still be the worse one when its deadline is tight


def test_levels_are_spread_not_all_the_same(truth):
    _, diseases = truth
    assert len({d["observed"]["severity_level"] for d in diseases if d["in_stage_a"]}) >= 2


def test_a_tighter_deadline_raises_the_score_with_nothing_else_changed():
    assert sev.deadline_urgency(-40) > sev.deadline_urgency(-1) > sev.deadline_urgency(1) > sev.deadline_urgency(20)
    assert sev.deadline_urgency(45) == 0.0
    assert sev.deadline_urgency(-1) >= sev.MISSED_BASE


def test_overrunning_the_estimate_raises_the_score():
    assert sev.overrun_score(0.5) == 0.0 and sev.overrun_score(1.0) == 0.0
    assert 0 < sev.overrun_score(4) < sev.overrun_score(40) < sev.overrun_score(10_000) == 1.0


def test_abnormality_scores_zero_at_the_median_and_one_at_the_extreme():
    assert sev.abnormality_from_percentile(0.5) == 0.0 and sev.abnormality_from_percentile(1.0) == 1.0
    assert sev.abnormality_from_percentile(None) == 0.0
    assert sev.abnormality_from_blocks(2) == 0.0 and sev.abnormality_from_blocks(10) == 1.0


def test_weights_sum_to_one():
    assert abs(sum(sev.WEIGHTS.values()) - 1.0) < 1e-9


def test_each_stage_a_type_is_described_in_the_severity_document():
    text = DOC.read_text(encoding="utf-8")
    for name in ("chronic_blocking", "recurring_blocker", "slow_chain"):
        assert name in text
    for term in ("deadline", "estimate", "abnormality", "overrun"):
        assert term in text.lower()
