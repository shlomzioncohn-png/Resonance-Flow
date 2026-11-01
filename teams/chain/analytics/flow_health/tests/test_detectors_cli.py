import json
from dataclasses import replace

import pytest

from flow_health.detectors.__main__ import main as detectors_cli
from flow_health.features.__main__ import main as features_cli
from flow_health.generator import GeneratorConfig, generate
from flow_health.generator.export import write_dataset

FLAG_KEYS = {"flag_id", "pattern", "entity_type", "entity_ids", "score", "severity", "explanation", "evidence"}


@pytest.fixture(scope="module")
def small(tmp_path_factory):
    data_dir, features = tmp_path_factory.mktemp("data"), tmp_path_factory.mktemp("features")
    data = generate(replace(GeneratorConfig(), n_normal_tasks=800, n_projects=8, instances_per_severity=2,
                            excluded_diseases=("stuck_subgraph",)))
    write_dataset(data_dir, data.config, data.world, data.history)
    assert features_cli(["--data", str(data_dir), "--out", str(features)]) == 0
    return data_dir, features


def _run(small, out):
    data_dir, features = small
    assert detectors_cli(["--data", str(data_dir), "--features", str(features), "--out", str(out), "--seed", "42"]) == 0
    return out.read_bytes()


def test_one_run_writes_a_flags_file_with_the_agreed_fields(small, tmp_path):
    payload = json.loads(_run(small, tmp_path / "flags.json"))
    assert payload["meta"]["seed"] == 42 and set(payload["meta"]["counts"]) == {
        "CHRONIC_BLOCKER", "RECURRING_CYCLE", "SLOW_CHAIN"}
    assert payload["flags"] and all(set(f) == FLAG_KEYS for f in payload["flags"])
    assert all(0 < f["score"] <= 1 and f["severity"] in ("low", "medium", "high") and f["explanation"]
               for f in payload["flags"])


def test_two_runs_write_byte_identical_files(small, tmp_path):
    assert _run(small, tmp_path / "a.json") == _run(small, tmp_path / "b.json")


def test_the_tenant_option_is_recorded_for_publishing(small, tmp_path):
    data_dir, features = small
    tenant = "aaaaaaaa-0000-0000-0000-000000000001"
    out = tmp_path / "flags.json"

    assert detectors_cli(["--data", str(data_dir), "--features", str(features), "--out", str(out),
                          "--seed", "42", "--tenant", tenant]) == 0

    assert json.loads(out.read_text(encoding="utf-8"))["meta"]["tenant_id"] == tenant
    # Without the option the file has no tenant, so publishing it is refused rather than guessed.
    other = tmp_path / "no_tenant.json"
    assert detectors_cli(["--data", str(data_dir), "--features", str(features), "--out", str(other),
                          "--seed", "42"]) == 0
    assert "tenant_id" not in json.loads(other.read_text(encoding="utf-8"))["meta"]
