from __future__ import annotations

import csv
import json
import statistics
from collections import Counter, defaultdict
from dataclasses import replace

import pytest

from flow_health.generator import DISEASE_TYPES, HOLDOUT_DISEASES, SEVERITIES, GeneratorConfig, generate, validate
from flow_health.generator.__main__ import main as generate_cli
from flow_health.generator.diseases import REGISTRY, Disease
from flow_health.generator.export import write_dataset
from flow_health.generator.model import TIER_PROFILES, TIERS

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


def test_diseases_outside_stage_a_are_labelled_so_the_detectors_can_skip_them(full, tmp_path):
    out_of_scope = {d.type for d in full.world.diseases if not d.in_stage_a}
    assert out_of_scope == {"growing_bottleneck", "stuck_subgraph"}
    write_dataset(tmp_path, full.config, full.world, full.history)
    with (tmp_path / "ground_truth.csv").open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    in_scope = {r["type"] for r in rows if r["in_stage_a"] == "true"}
    assert in_scope == {"chronic_blocking", "recurring_blocker", "slow_chain"}


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
    blocks = defaultdict(int)
    for r in rows:
        if r.event_type == "BLOCKED":
            blocks[r.task_id] += 1

    for d in full.world.diseases:
        days_available = (end - d.start).days  # late starters are cut off by the end of the history
        if d.type in ("chronic_blocking", "stuck_subgraph") and d.severity == "severe":
            assert max(blocked_days[t] for t in d.task_ids) >= 0.75 * min(d.params["hold_days"], days_available) - 3, d.id
        if d.type == "recurring_blocker":
            assert max(blocks[t] for t in d.task_ids) >= min(d.params["cycles"], days_available // 7) - 1, d.id
        if d.type == "growing_bottleneck":
            assert len(d.task_ids) == d.params["dependents"] + 1


def test_no_task_ever_leaves_done(full):
    assert not [r for r in full.history.state_rows if r.event_type == "STATUS_CHANGED" and r.from_status == "DONE"]


def test_chronic_and_stuck_roots_are_long_for_their_own_tier(full):
    for d in full.world.diseases:
        if d.type in ("chronic_blocking", "stuck_subgraph"):
            assert d.params["hold_days"] >= 3 * d.params["peer_typical_days"], d.id


def test_normal_world_has_healthy_lookalikes_and_dead_ends(full):
    kinds = {t.kind for t in full.world.tasks}
    assert {"slow_legit", "backlog", "abandoned"} <= kinds
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
    normal = [t for t in full.world.tasks if t.kind not in ("disease", "control_chain")]
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
    per_day = Counter(t.created_at.date() for t in full.world.tasks if t.kind not in ("disease", "control_chain"))
    counts = sorted(per_day.values())
    assert counts[-1] >= 3 * statistics.median(counts)


def test_graph_has_fan_in_and_fan_out_not_just_chains(full):
    deps, dependents = Counter(), Counter()
    for r in full.history.dependency_rows:
        if r.action == "ADDED":
            deps[r.task_id] += 1
            dependents[r.depends_on_task_id] += 1
    assert max(deps.values()) >= 5 and max(dependents.values()) >= 6


def test_activity_is_mostly_in_working_hours_but_not_only(full):
    created = [r.created_at for r in full.history.state_rows if r.event_type == "STATUS_CHANGED"]
    off_hours = sum(1 for t in created if t.weekday() >= 5 or not 8 <= t.hour < 19)
    assert 0.05 < off_hours / len(created) < 0.30  # mostly office hours, but not exclusively


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


# ---- peers: similar subtree, controlled chains, no tier ----

def _truth(data, tmp_path):
    write_dataset(tmp_path, data.config, data.world, data.history)
    return json.loads((tmp_path / "ground_truth.json").read_text(encoding="utf-8"))["diseases"]


def test_a_blocker_is_compared_with_healthy_blockers_of_the_same_tier_and_similar_subtree(full, tmp_path):
    roots = [d["observed"]["peer_group"] for d in _truth(full, tmp_path) if d["type"] == "chronic_blocking"]
    assert all(g["n_peers"] >= 20 for g in roots)
    similar = [g for g in roots if g["level"].startswith("tier+subtree")]
    assert len(similar) >= 0.8 * len(roots)
    assert all(g["tier_used"] and g["subtree_bucket"] for g in similar)


def test_healthy_control_chains_exist_and_are_linear(full):
    lengths = {c.length for c in full.world.controls}
    assert lengths == {4, 7, 12} and all(c.shape == "linear" for c in full.world.controls)
    edges = {(r.task_id, r.depends_on_task_id) for r in full.history.dependency_rows if r.action == "ADDED"}
    for c in full.world.controls:
        assert all((c.task_ids[i], c.task_ids[i - 1]) in edges for i in range(1, c.length))


def test_a_slow_chain_is_compared_with_healthy_chains_of_the_same_length_tier_and_pace(full, tmp_path):
    chains = [d["observed"]["peer_chains"] for d in _truth(full, tmp_path) if d["type"] == "slow_chain"]
    assert all(c["n_peers"] >= 5 for c in chains)
    assert sum(c["level"] == "length+tier+team_pace" for c in chains) >= 0.8 * len(chains)
    severe_done = [c for c, d in zip(chains, [d for d in _truth(full, tmp_path) if d["type"] == "slow_chain"])
                   if d["severity"] == "severe" and c["completed"]]
    assert severe_done and all(c["end_to_end_days"] > 1.5 * c["p50_days"] for c in severe_done)


def test_everything_works_when_no_task_has_a_known_tier(tmp_path):
    cfg = replace(SMALL, excluded_diseases=(), missing_tier_rate=1.0)
    data = generate(cfg)
    assert _errors(data) == []
    truth = _truth(data, tmp_path)
    assert all(d["observed"]["peer_group"]["tier_used"] is None for d in truth)
    assert all("tier" not in d["observed"]["peer_group"]["level"] for d in truth)
    assert all(d["observed"]["peer_chains"]["tier_used"] is None for d in truth if d["type"] == "slow_chain")
    with (tmp_path / "task_meta.csv").open(encoding="utf-8") as f:
        visible = list(csv.DictReader(f))
    with (tmp_path / "answer_key_tasks.csv").open(encoding="utf-8") as f:
        key = list(csv.DictReader(f))
    assert all(r["tier"] == "" for r in visible)
    assert all(r["true_tier"] for r in key)  # hidden from the analysis, kept in the key


def test_the_simulation_runs_for_tasks_that_have_no_tier_at_all():
    from flow_health.generator.planner import plan_world
    from flow_health.generator.simulation import simulate

    world = plan_world(SMALL)
    for i, task in enumerate(world.tasks):
        if i % 5 == 0:
            task.tier = None
    history = simulate(SMALL, world)
    assert validate(history.dependency_rows, history.state_rows) == []


def test_some_tiers_are_hidden_by_default_and_the_rest_are_visible(full, tmp_path):
    write_dataset(tmp_path, full.config, full.world, full.history)
    with (tmp_path / "task_meta.csv").open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    hidden = sum(1 for r in rows if r["tier"] == "") / len(rows)
    assert abs(hidden - full.config.missing_tier_rate) < 0.02


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
        with (tmp_path / name / "tasks.csv").open(encoding="utf-8") as f:
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
