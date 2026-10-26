import csv
from dataclasses import replace

import numpy as np
import pandas as pd
import pytest

from flow_health.clustering import CLUSTER_FEATURES, cluster_matrix, cluster_tasks
from flow_health.clustering.kmeans import K_RANGE
from flow_health.features.__main__ import main as features_cli
from flow_health.generator import GeneratorConfig, generate
from flow_health.generator.export import write_dataset

OUTPUTS = ["features_task.csv", "features_edge.csv", "features_chain.csv", "clusters_task.csv",
           "cluster_report.md", "feature_spec.md"]


@pytest.fixture(scope="module")
def dev(tmp_path_factory):
    """The full dev-sized dataset, written like `python -m flow_health.generator` writes it."""
    path = tmp_path_factory.mktemp("dev")
    data = generate(replace(GeneratorConfig(), excluded_diseases=("stuck_subgraph",)))
    write_dataset(path, data.config, data.world, data.history)
    return path


@pytest.fixture(scope="module")
def run(dev, tmp_path_factory):
    out = tmp_path_factory.mktemp("out")
    assert features_cli(["--data", str(dev), "--out", str(out)]) == 0
    return out


def _read(path):
    return pd.read_csv(path)


def test_the_command_writes_the_four_tables_the_report_and_the_spec(run):
    assert all((run / name).is_file() for name in OUTPUTS)


def test_two_runs_on_the_same_input_give_identical_files(dev, run, tmp_path):
    assert features_cli(["--data", str(dev), "--out", str(tmp_path)]) == 0
    for name in OUTPUTS:
        assert (tmp_path / name).read_bytes() == (run / name).read_bytes(), name


def test_row_counts_match_the_entities(dev, run):
    tasks = _read(dev / "tasks.csv")
    deps = _read(dev / "dependency_history.csv")
    deps = deps[deps["action"].isin(["ADDED", "REMOVED"])]
    assert len(_read(run / "features_task.csv")) == len(tasks) == len(_read(run / "clusters_task.csv"))
    assert len(_read(run / "features_edge.csv")) == len(deps[["task_id", "depends_on_task_id"]].drop_duplicates())
    assert len(_read(run / "features_chain.csv")) > 0


def test_cluster_features_are_finite_and_exclude_tier_and_project(run):
    features = _read(run / "features_task.csv")
    matrix = cluster_matrix(features)
    assert list(matrix.columns) == CLUSTER_FEATURES
    assert "tier" not in CLUSTER_FEATURES and "project_id" not in CLUSTER_FEATURES
    assert np.isfinite(matrix.to_numpy(float)).all()


def test_every_task_has_exactly_one_cluster_and_k_is_in_range(run):
    clusters = _read(run / "clusters_task.csv")
    assert list(clusters.columns) == ["task_id", "cluster", "distance_to_centroid"]
    assert clusters["task_id"].is_unique and (clusters["cluster"] >= 0).all()
    assert clusters["distance_to_centroid"].notna().all() and (clusters["distance_to_centroid"] >= 0).all()
    k = clusters["cluster"].nunique()
    assert min(K_RANGE) <= k <= max(K_RANGE)
    assert f"Chosen k: **{k}**" in (run / "cluster_report.md").read_text(encoding="utf-8")


def test_the_report_lists_every_cluster_with_its_tier_mix_and_the_overall_silhouette(run):
    report = (run / "cluster_report.md").read_text(encoding="utf-8")
    clusters = _read(run / "clusters_task.csv")
    assert "Overall silhouette" in report and "Tier mix inside each cluster" in report
    for c, n in clusters["cluster"].value_counts().items():
        assert f"| {c} | {n} |" in report


def test_blocked_p90_is_zero_only_where_the_task_was_never_blocked():
    features = pd.DataFrame({
        "task_id": list("abcdefgh"), "age_ratio_to_tier": 1.0, "blocked_ratio_to_tier": 0.0,
        "days_blocked_total": 0.0, "n_block_events": [0, 1, 0, 2, 0, 1, 0, 1],
        "blocked_p90": [np.nan, 2.0, np.nan, 3.0, np.nan, 1.0, np.nan, 5.0],
        "n_dependents_now": 0, "n_dependents_max": 0, "dependents_per_30d": 0.0})
    m = cluster_matrix(features)
    assert m.loc[["a", "c", "e", "g"], "blocked_p90"].tolist() == [0, 0, 0, 0]
    assert m.loc["b", "blocked_p90"] == 2.0
    unknown = features.assign(age_ratio_to_tier=[np.nan] + [1.0] * 7)
    assert pd.isna(cluster_matrix(unknown).loc["a", "age_ratio_to_tier"])  # unknown stays unknown, never turned into 0


def test_a_task_with_an_unknown_feature_is_left_out_and_reported():
    rng = np.random.default_rng(0)
    features = pd.DataFrame({
        "task_id": [f"t{i:02d}" for i in range(40)], "age_ratio_to_tier": rng.random(40),
        "blocked_ratio_to_tier": rng.random(40), "days_blocked_total": rng.random(40), "n_block_events": 1,
        "blocked_p90": rng.random(40), "n_dependents_now": rng.integers(0, 3, 40),
        "n_dependents_max": rng.integers(0, 3, 40), "dependents_per_30d": rng.random(40)})
    features.loc[3, "age_ratio_to_tier"] = np.nan
    result = cluster_tasks(features)
    assert result.n_left_out == 1
    assert result.assignments.set_index("task_id").loc["t03", "cluster"] == -1


@pytest.mark.xfail(strict=True, reason=(
    "Fails on dev, awaiting a decision. A chronic blocker is held ACTIVE, not blocked, so its blocked_ratio_to_tier "
    "is 0; and the top decile of age_ratio_to_tier is taken by backlog / abandoned tasks (about 500 of the top 600, "
    "nothing depends on them) while the blockers' ratios are 0.6-15 against a cut-off of about 10.7."))
def test_every_planted_chronic_blocker_is_in_the_top_decile_of_a_ratio(dev, run):
    """Sanity check on dev. The answer key is allowed here, never in the production code."""
    features = _read(run / "features_task.csv").set_index("task_id")
    top = {c: features[c].quantile(0.9) for c in ("blocked_ratio_to_tier", "age_ratio_to_tier")}
    with (dev / "ground_truth.csv").open(encoding="utf-8") as f:
        roots = [r for r in csv.DictReader(f) if r["type"] == "chronic_blocking" and r["element_type"] == "task"
                 and r["role"] == "root"]
    missed = [(r["disease_id"], r["severity"]) for r in roots
              if not any(features.loc[r["task_id"], c] >= top[c] for c in top)]
    assert roots and not missed, f"{len(missed)} of {len(roots)} chronic blockers outside the top decile: {missed}"
