"""The mentor's review notes: recurring blockers must not be trivial, late starters are marked, eval is sealed."""
from __future__ import annotations

import json
from collections import Counter, defaultdict
from dataclasses import replace
from datetime import timedelta

import pytest

from flow_health.generator import GeneratorConfig, generate
from flow_health.generator.__main__ import main as generate_cli
from flow_health.generator.export import write_dataset
from flow_health.generator.seal import SEAL_NAME, seal, verify

FULL = replace(GeneratorConfig(), excluded_diseases=())
SMALL = replace(GeneratorConfig(), n_normal_tasks=300, n_projects=6, instances_per_severity=1)


@pytest.fixture(scope="module")
def full():
    return generate(FULL)


def _blocks(data):
    counts = defaultdict(list)
    for r in data.history.state_rows:
        if r.event_type == "BLOCKED":
            counts[r.task_id].append(r.created_at)
    return counts


# ---- 1. a rule that only counts blocks must not catch every recurring blocker and nothing else ----

def test_some_healthy_tasks_are_blocked_two_and_three_times(full):
    kind = {t.id: t.kind for t in full.world.tasks}
    healthy = {t: v for t, v in _blocks(full).items() if kind[t] != "disease"}
    histogram = Counter(len(v) for v in healthy.values())
    assert histogram[2] >= 20 and histogram[3] >= 10
    assert max(histogram) == 3  # healthy tasks stay at 3 or fewer; more than that is the disease
    assert {kind[t] for t, v in healthy.items() if len(v) >= 2} >= {"replanned_legit"}


def test_counting_blocks_alone_flags_healthy_tasks_and_misses_nothing_it_should_not(full):
    blocks = _blocks(full)
    kind = {t.id: t.kind for t in full.world.tasks}
    flagged = {t for t, v in blocks.items() if len(v) >= 3}
    healthy_flagged = [t for t in flagged if kind[t] != "disease"]
    planted = {d.id: max(len(blocks[t]) for t in d.task_ids) for d in full.world.diseases if d.type == "recurring_blocker"}
    assert healthy_flagged  # the rule has false positives
    assert all(n >= 3 for n in planted.values())  # and it still finds every planted one
    assert all(n > 3 for i, n in planted.items() if "subtle" not in i)  # the stronger ones go past anything healthy
    assert any(n == 3 for n in planted.values())  # but the subtle ones sit exactly on the healthy ceiling


def test_replanned_tasks_are_labelled_as_healthy_in_the_answer_key(full):
    assert any(t.kind == "replanned_legit" for t in full.world.tasks)


# ---- 2. patterns that start too close to the end are marked, not silently counted ----

def test_ground_truth_says_whether_each_disease_is_visible_in_the_data(full, tmp_path):
    write_dataset(tmp_path, full.config, full.world, full.history)
    for d in json.loads((tmp_path / "ground_truth.json").read_text(encoding="utf-8"))["diseases"]:
        o = d["observed"]
        assert o["visible_in_data"] == (o["visible_from"] is not None)
        if o["visible_in_data"]:
            assert o["not_visible_because"] is None and d["start"] <= o["visible_from"] <= o["window_end"]
        else:
            assert o["not_visible_because"] == ("too_recent" if o["ongoing_at_end"] else "stayed_within_normal")


def test_a_chronic_blocker_becomes_visible_when_it_passes_the_p90_of_its_peers(full, tmp_path):
    from datetime import datetime

    write_dataset(tmp_path, full.config, full.world, full.history)
    diseases = json.loads((tmp_path / "ground_truth.json").read_text(encoding="utf-8"))["diseases"]
    created = {str(t.id): t.created_at for t in full.world.tasks}
    visible = [d for d in diseases if d["type"] == "chronic_blocking" and d["observed"]["visible_in_data"]]
    assert visible
    for d in visible:
        expected = created[d["root_task_id"]] + timedelta(days=d["observed"]["peer_group"]["p90_days"])
        assert datetime.fromisoformat(d["observed"]["visible_from"]) == expected


def test_a_pattern_that_starts_just_before_the_end_is_not_yet_visible():
    data = generate(replace(FULL, seed=4242))  # a seed whose ongoing patterns started late (the retired first eval seed)
    from flow_health.generator.export import _observed
    from flow_health.generator.peers import PeerIndex

    observed = _observed(data.config, data.world, data.history, PeerIndex(data.config, data.world, data.history))
    late = [i for i, o in observed.items() if o["ongoing_at_end"] and not o["visible_in_data"]]
    assert late and all(observed[i]["not_visible_because"] == "too_recent" for i in late)


def test_every_stage_a_disease_type_starts_along_the_whole_timeline_not_only_at_the_end(full):
    span = (full.config.end - full.config.start).total_seconds()
    for name in ("chronic_blocking", "recurring_blocker", "slow_chain"):
        fifths = Counter(min(int((d.start - full.config.start).total_seconds() / span * 5), 4)
                         for d in full.world.diseases if d.type == name)
        assert fifths[0] >= 1 and fifths[3] + fifths[4] >= 1  # some at the beginning, some late
        assert len([f for f in fifths.values() if f]) >= 4  # spread over most of the timeline
        assert max(fifths.values()) <= 0.6 * sum(fifths.values())  # no single stretch holds most of them


# ---- 3. the evaluation set is sealed ----

def test_a_written_dataset_is_sealed_and_untouched(tmp_path):
    cfg = SMALL
    data = generate(cfg)
    write_dataset(tmp_path, cfg, data.world, data.history)
    assert (tmp_path / SEAL_NAME).is_file() and verify(tmp_path) == []


def test_editing_adding_or_removing_a_file_breaks_the_seal(tmp_path):
    data = generate(SMALL)
    write_dataset(tmp_path, SMALL, data.world, data.history)
    path = tmp_path / "task_state_history.csv"
    original = path.read_bytes()  # bytes: text mode would rewrite the line endings on Windows
    path.write_bytes(original + b" ")
    assert verify(tmp_path) == ["task_state_history.csv: changed"]
    path.write_bytes(original)
    (tmp_path / "extra.csv").write_text("x", encoding="utf-8")
    assert verify(tmp_path) == ["extra.csv: not in the seal"]
    (tmp_path / "extra.csv").unlink()
    (tmp_path / "tasks.csv").unlink()
    assert verify(tmp_path) == ["tasks.csv: missing"]


def test_the_same_seed_gives_the_same_seal(tmp_path):
    first, second = tmp_path / "a", tmp_path / "b"
    for out in (first, second):
        data = generate(SMALL)
        write_dataset(out, SMALL, data.world, data.history)
    assert (first / SEAL_NAME).read_text() == (second / SEAL_NAME).read_text()


def test_the_command_seals_both_sets_and_can_verify_them(tmp_path, capsys):
    assert generate_cli(["--out", str(tmp_path), "--tasks", "300", "--projects", "6", "--instances", "1"]) == 0
    seeds = {name: json.loads((tmp_path / name / SEAL_NAME).read_text())["seed"] for name in ("dev", "eval")}
    assert seeds["dev"] != seeds["eval"]
    assert generate_cli(["--verify", str(tmp_path / "dev"), str(tmp_path / "eval")]) == 0
    (tmp_path / "eval" / "ground_truth.csv").write_text("tampered", encoding="utf-8")
    assert generate_cli(["--verify", str(tmp_path / "eval")]) == 1
    assert "ground_truth.csv: changed" in capsys.readouterr().out


def test_a_directory_without_a_seal_does_not_verify(tmp_path):
    assert verify(tmp_path) == [f"{tmp_path}: no {SEAL_NAME}"]
    seal(tmp_path, 1, "0")
    assert verify(tmp_path) == []


# ---- the generator version ----

GOLDEN_VERSION = "1.0.1"
GOLDEN_DIGEST = "2b67f3359e2c5b52b91ab73aa8035b99247b9b3fa21740cf21ee5d35c790bdb0"  # of the SMALL dataset below


def test_manifest_and_seal_record_the_generator_version(tmp_path):
    from flow_health.generator import GENERATOR_VERSION

    data = generate(SMALL)
    write_dataset(tmp_path, SMALL, data.world, data.history)
    assert json.loads((tmp_path / "manifest.json").read_text())["generator_version"] == GENERATOR_VERSION
    assert json.loads((tmp_path / SEAL_NAME).read_text())["generator_version"] == GENERATOR_VERSION


def test_the_version_is_bumped_whenever_the_data_a_seed_gives_changes(tmp_path):
    """If this fails you changed what the generator produces: bump GENERATOR_VERSION (version.py), then set
    GOLDEN_VERSION and GOLDEN_DIGEST here to the new values, and re-seal / regenerate the evaluation set."""
    import hashlib

    from flow_health.generator import GENERATOR_VERSION

    data = generate(SMALL)
    write_dataset(tmp_path, SMALL, data.world, data.history)
    files = json.loads((tmp_path / SEAL_NAME).read_text())["files"]
    digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    assert (GENERATOR_VERSION, digest) == (GOLDEN_VERSION, GOLDEN_DIGEST)


def test_verify_says_so_when_the_code_is_newer_than_the_seal(tmp_path, capsys, monkeypatch):
    data = generate(SMALL)
    write_dataset(tmp_path, SMALL, data.world, data.history)
    monkeypatch.setattr("flow_health.generator.__main__.GENERATOR_VERSION", "9.9.9")
    assert generate_cli(["--verify", str(tmp_path)]) == 0
    assert "sealed with generator 1.0.1, this code is 9.9.9" in capsys.readouterr().out
