from __future__ import annotations

from collections import defaultdict
from dataclasses import replace

import pytest

from health.generator import DISEASE_TYPES, HOLDOUT_DISEASES, SEVERITIES, GeneratorConfig, generate, validate

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
            assert dev_by_id[d.id].task_ids == d.task_ids  # same ids, so dev results carry over to final


def test_every_severity_and_type_is_planted(full):
    planted = {(d.type, d.severity) for d in full.world.diseases}
    assert planted == {(t, s) for t in DISEASE_TYPES for s in SEVERITIES}


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


# ---- the validator must actually catch things, otherwise the test above proves nothing ----

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


def test_rejected_rows_exist_and_never_change_the_graph(full):
    rejected = [r for r in full.history.dependency_rows if r.action == "REJECTED"]
    assert rejected
    # a rejected pair was never an ADDED edge (validate() already replays without them)
    added = {(r.task_id, r.depends_on_task_id) for r in full.history.dependency_rows if r.action == "ADDED"}
    assert not any((r.task_id, r.depends_on_task_id) in added for r in rejected)


def test_some_diseases_are_still_ongoing_and_some_resolved(full, tmp_path):
    import json
    from health.generator.export import write_dataset

    write_dataset(tmp_path, full.config, full.world, full.history)
    observed = [d["observed"]["ongoing_at_end"] for d in json.loads((tmp_path / "ground_truth.json").read_text())["diseases"]]
    assert any(observed) and not all(observed)
    assert (tmp_path / "ground_truth.csv").exists()
