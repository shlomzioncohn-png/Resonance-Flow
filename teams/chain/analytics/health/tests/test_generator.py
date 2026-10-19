from __future__ import annotations

import csv
import json
import statistics
from collections import Counter, defaultdict
from dataclasses import replace

import pytest

from health.generator import DISEASE_TYPES, HOLDOUT_DISEASES, SEVERITIES, GeneratorConfig, generate, validate
from health.generator.__main__ import main as generate_cli
from health.generator.diseases import REGISTRY, Disease
from health.generator.export import write_dataset
from health.generator.model import TIER_PROFILES, TIERS

SMALL = replace(GeneratorConfig(), n_normal_tasks=300, n_projects=6, instances_per_severity=1)


@pytest.fixture(scope="module")
def full():
    return generate(replace(GeneratorConfig(), excluded_diseases=()))


def _errors(data):
    return validate(data.history.dependency_rows, data.history.state_rows)


def test_generated_history_is_consistent(full):
    assert _errors(full) == []


def test_same_seed_is_identical_and_other_seed_differs():
    a, b = generate(SMALL), generate(SMALL)
    assert a.history.dependency_rows == b.history.dependency_rows
    assert a.history.state_rows == b.history.state_rows
    c = generate(replace(SMALL, seed=7))
    assert c.history.state_rows != a.history.state_rows


def test_holdout_is_excluded_by_default_and_leaves_other_diseases_untouched():
    dev = generate(SMALL)
    final = generate(replace(SMALL, excluded_diseases=()))
    assert {d.type for d in dev.world.diseases} == set(DISEASE_TYPES) - set(HOLDOUT_DISEASES)
    assert {d.type for d in final.world.diseases} == set(DISEASE_TYPES)
    dev_by_id = {d.id: d for d in dev.world.diseases}
    for d in final.world.diseases:
        if d.type not in HOLDOUT_DISEASES:
            assert dev_by_id[d.id].task_ids == d.task_ids  # same ids, so dev results carry over


def test_every_severity_and_type_is_planted(full):
    planted = {(d.type, d.severity) for d in full.world.diseases}
    assert planted == {(t, s) for t in DISEASE_TYPES for s in SEVERITIES}
    assert {"chronic_blocking", "recurring_blocker", "slow_chain"} <= set(DISEASE_TYPES)  # the three required


def test_sick_share_is_a_small_minority(full):
    sick = {t for d in full.world.diseases for t in d.task_ids}
    assert 0.03 < len(sick) / len(full.world.tasks) < 0.20


def test_planted_diseases_leave_their_signature(full):
    rows = full.history.state_rows
    end = full.config.end
    blocked_days = defaultdict(float)  # longest block per task, open blocks measured up to `end`
    opened = {}
    for r in rows:
        if r.event_type == "BLOCKED":
            opened[r.task_id] = r.created_at
        elif r.event_type == "UNBLOCKED":
            blocked_days[r.task_id] = max(blocked_days[r.task_id], (r.created_at - opened.pop(r.task_id)).days)
    for task, since in opened.items():
        blocked_days[task] = max(blocked_days[task], (end - since).days)
    done = defaultdict(int)
    for r in rows:
        if r.event_type == "STATUS_CHANGED" and r.to_status == "DONE":
            done[r.task_id] += 1

    for d in full.world.diseases:
        days_available = (end - d.start).days  # late starters are cut off by the end of the history
        if d.type in ("chronic_blocking", "stuck_subgraph") and d.severity == "severe":
            assert max(blocked_days[t] for t in d.task_ids) >= 0.75 * min(d.params["hold_days"], days_available) - 3, d.id
        if d.type == "recurring_blocker":
            assert done[d.root_task_id] >= min(d.params["cycles"], days_available // 9) - 1, d.id
        if d.type == "growing_bottleneck":
            assert len(d.task_ids) == d.params["dependents"] + 1


def test_chronic_and_stuck_roots_are_long_for_their_own_tier(full):
    for d in full.world.diseases:
        if d.type in ("chronic_blocking", "stuck_subgraph"):
            assert d.params["hold_days"] >= 3 * d.params["peer_typical_days"], d.id


def test_normal_world_has_healthy_lookalikes_and_dead_ends(full):
    kinds = {t.kind for t in full.world.tasks}
    assert {"slow_legit", "reopened_legit", "backlog", "abandoned"} <= kinds
    # nothing depends on backlog/abandoned tasks, so they can't masquerade as chronic blockers
    dead = {t.id for t in full.world.tasks if t.kind in ("backlog", "abandoned")}
    real = [e for e in full.history.dependency_rows if e.action != "REJECTED"]
    assert not any(e.depends_on_task_id in dead for e in real)


def test_every_task_has_a_valid_final_status(full):
    assert set(full.history.final_status.values()) <= {"PENDING", "ACTIVE", "DONE"}
    assert any(s != "DONE" for s in full.history.final_status.values())  # history ends mid-flight


def test_rejected_rows_exist_and_never_change_the_graph(full):
    rejected = [r for r in full.history.dependency_rows if r.action == "REJECTED"]
    assert rejected
    added = {(r.task_id, r.depends_on_task_id) for r in full.history.dependency_rows if r.action == "ADDED"}
    assert not any((r.task_id, r.depends_on_task_id) in added for r in rejected)


# ---- tiers, bursts, graph shapes ----

def test_tiers_exist_with_their_expected_shares(full):
    normal = [t for t in full.world.tasks if t.kind != "disease"]
    counts = Counter(t.tier for t in normal)
    assert set(counts) == set(TIERS)
    for tier in TIERS:
        assert abs(counts[tier] / len(normal) - TIER_PROFILES[tier].share) < 0.03


def test_higher_tiers_take_longer_to_finish(full):
    tier = {t.id: t.tier for t in full.world.tasks if t.kind == "normal"}
    started, took = {}, defaultdict(list)
    for r in full.history.state_rows:
        if r.event_type != "STATUS_CHANGED" or r.task_id not in tier:
            continue
        if r.to_status == "ACTIVE" and r.from_status == "PENDING":
            started[r.task_id] = r.created_at
        elif r.to_status == "DONE" and r.task_id in started:
            took[tier[r.task_id]].append((r.created_at - started[r.task_id]).total_seconds())
    medians = [statistics.median(took[t]) for t in TIERS]
    assert medians == sorted(medians) and medians[0] < medians[-1] / 2


def test_task_creation_comes_in_bursts(full):
    per_day = Counter(t.created_at.date() for t in full.world.tasks if t.kind != "disease")
    counts = sorted(per_day.values())
    assert counts[-1] >= 3 * statistics.median(counts)


def test_graph_has_fan_in_and_fan_out_not_just_chains(full):
    deps, dependents = Counter(), Counter()
    for r in full.history.dependency_rows:
        if r.action == "ADDED":
            deps[r.task_id] += 1
            dependents[r.depends_on_task_id] += 1
    assert max(deps.values()) >= 5 and max(dependents.values()) >= 6


def test_working_hours_and_weekends_are_respected(full):
    created = [r.created_at for r in full.history.state_rows if r.event_type == "STATUS_CHANGED"]
    off_hours = sum(1 for t in created if t.weekday() >= 5 or not 8 <= t.hour < 20)
    assert off_hours / len(created) < 0.02


# ---- ground truth ----

def test_ground_truth_describes_tasks_edges_window_and_severity(full, tmp_path):
    write_dataset(tmp_path, full.config, full.world, full.history)
    with (tmp_path / "ground_truth.csv").open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert {r["element_type"] for r in rows} == {"task", "edge"}
    assert all(r["severity"] in SEVERITIES and r["window_start"] and r["window_end"] for r in rows)
    assert all(r["tier"] in TIERS for r in rows)
    assert all(r["depends_on_task_id"] for r in rows if r["element_type"] == "edge")
    assert all(r["window_start"] <= r["window_end"] for r in rows)
    truth = json.loads((tmp_path / "ground_truth.json").read_text(encoding="utf-8"))["diseases"]
    ongoing = [d["observed"]["ongoing_at_end"] for d in truth]
    assert any(ongoing) and not all(ongoing)


# ---- dev / eval in one command ----

def test_one_command_writes_independent_dev_and_eval_sets(tmp_path):
    assert generate_cli(["--out", str(tmp_path), "--tasks", "300", "--projects", "6", "--instances", "1"]) == 0
    dev_manifest = json.loads((tmp_path / "dev" / "manifest.json").read_text())
    eval_manifest = json.loads((tmp_path / "eval" / "manifest.json").read_text())
    assert dev_manifest["seed"] != eval_manifest["seed"]
    assert dev_manifest["disease_types_held_out"] == list(HOLDOUT_DISEASES)
    assert eval_manifest["disease_types_held_out"] == []
    ids = []
    for name in ("dev", "eval"):
        with (tmp_path / name / "tasks_meta.csv").open(encoding="utf-8") as f:
            ids.append({r["task_id"] for r in csv.DictReader(f)})
        for table in ("dependency_history", "task_state_history", "ground_truth"):
            assert (tmp_path / name / f"{table}.csv").is_file()
    assert not ids[0] & ids[1]  # eval shares no task with dev


def test_dev_and_eval_must_use_different_seeds(tmp_path):
    with pytest.raises(SystemExit):
        generate_cli(["--out", str(tmp_path), "--seed", "5", "--eval-seed", "5"])


# ---- adding a disease needs no change to the rest of the generator ----

def test_a_new_disease_module_plugs_in_through_the_common_interface(monkeypatch):
    def plant(p, severity):
        root = p.task(p.t0, hold_finish_until=p.hold_end(5))
        p.depend(p.task(p.advance(2)), [root])
        return root, {"note": "toy"}

    monkeypatch.setitem(REGISTRY, "toy", Disease("toy", plant))
    data = generate(replace(SMALL, excluded_diseases=()))
    toys = [d for d in data.world.diseases if d.type == "toy"]
    assert len(toys) == len(SEVERITIES) and all(len(d.task_ids) == 2 for d in toys)
    assert _errors(data) == []


def test_every_disease_module_follows_the_interface():
    assert set(REGISTRY) == set(DISEASE_TYPES)
    for name, disease in REGISTRY.items():
        assert disease.name == name and callable(disease.plant)


# ---- the validator must actually catch things, otherwise the consistency test proves nothing ----

def test_validator_flags_a_wrong_blocked_count(full):
    rows = list(full.history.state_rows)
    i = next(i for i, r in enumerate(rows) if r.event_type == "BLOCKED")
    rows[i] = replace(rows[i], blocking_count_after=rows[i].blocking_count_after + 1)
    assert validate(full.history.dependency_rows, rows)


def test_validator_flags_a_missing_unblocked(full):
    rows = list(full.history.state_rows)
    i = next(i for i, r in enumerate(rows) if r.event_type == "UNBLOCKED")
    del rows[i]
    assert validate(full.history.dependency_rows, rows)


def test_validator_flags_a_duplicate_source_ref(full):
    rows = list(full.history.dependency_rows)
    i = next(i for i, r in enumerate(rows) if r.source_ref)
    j = next(j for j, r in enumerate(rows) if r.source_ref and j > i and r.source == rows[i].source)
    rows[j] = replace(rows[j], source_ref=rows[i].source_ref)
    assert validate(rows, full.history.state_rows)


def test_validator_flags_a_removed_edge_that_never_existed(full):
    rows = list(full.history.dependency_rows)
    i = next(i for i, r in enumerate(rows) if r.action == "ADDED")
    rows[i] = replace(rows[i], action="REMOVED")
    assert validate(rows, full.history.state_rows)


def test_validator_flags_a_wrong_status_chain(full):
    rows = list(full.history.state_rows)
    i = next(i for i, r in enumerate(rows) if r.event_type == "STATUS_CHANGED" and r.from_status == "ACTIVE")
    rows[i] = replace(rows[i], from_status="PENDING")
    assert validate(full.history.dependency_rows, rows)
