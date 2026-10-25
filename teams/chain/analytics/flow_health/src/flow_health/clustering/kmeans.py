"""One KMeans clustering of the tasks: what "normal behaviour" looks like. It describes, it does not flag."""
from __future__ import annotations

import logging
from dataclasses import dataclass

import numpy as np
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from sklearn.preprocessing import StandardScaler

log = logging.getLogger(__name__)

# tier and project_id are left out on purpose: the ratios to the tier median already account for the tier
CLUSTER_FEATURES = [
    "age_ratio_to_tier", "blocked_ratio_to_tier", "days_blocked_total", "n_block_events", "blocked_p90",
    "n_dependents_now", "n_dependents_max", "dependents_per_30d",
]
# empty only because the task was never blocked ("did not happen"), so it becomes 0 in the matrix
NOT_HAPPENED = {"blocked_p90": "n_block_events"}
K_RANGE = range(2, 7)
RANDOM_STATE = 42
SILHOUETTE_SAMPLE = 10_000  # silhouette is quadratic: beyond this a fixed-seed sample is scored


@dataclass(frozen=True)
class ClusterResult:
    assignments: pd.DataFrame  # task_id, cluster, distance_to_centroid (cluster -1 = left out, distance empty)
    k: int
    silhouette: float
    silhouette_by_k: dict[int, float]
    matrix: pd.DataFrame  # the unscaled cluster features of the clustered tasks, indexed by task_id
    n_left_out: int


def cluster_matrix(features: pd.DataFrame) -> pd.DataFrame:
    """Cluster features indexed by task_id: 0 where the value is 'did not happen', empty where it is unknown."""
    m = features.set_index("task_id")[sorted(set(CLUSTER_FEATURES) | set(NOT_HAPPENED.values()))].copy()
    for column, counter in NOT_HAPPENED.items():
        m[column] = m[column].where(m[counter] > 0, 0.0)
    return m[CLUSTER_FEATURES].replace([np.inf, -np.inf], np.nan)


def cluster_tasks(features: pd.DataFrame, seed: int = RANDOM_STATE) -> ClusterResult:
    full = cluster_matrix(features)
    matrix = full.dropna()
    left_out = len(full) - len(matrix)
    if left_out:
        log.warning("%d tasks have an unknown cluster feature and are left out of the clustering", left_out)
    if len(matrix) <= max(K_RANGE):
        raise ValueError(f"need more than {max(K_RANGE)} tasks to cluster, got {len(matrix)}")

    x = StandardScaler().fit_transform(matrix.to_numpy(float))
    scores, models = {}, {}
    for k in K_RANGE:
        model = KMeans(n_clusters=k, random_state=seed, n_init=10).fit(x)
        sample = SILHOUETTE_SAMPLE if len(x) > SILHOUETTE_SAMPLE else None
        scores[k] = float(silhouette_score(x, model.labels_, sample_size=sample, random_state=seed))
        models[k] = model
    k = max(scores, key=lambda c: (scores[c], -c))  # best silhouette; the smaller k on a tie
    model = models[k]
    # rounded: threaded float sums in KMeans differ in the last bits between runs, and the output must be identical
    distance = np.linalg.norm(x - model.cluster_centers_[model.labels_], axis=1).round(9)

    done = pd.DataFrame({"task_id": matrix.index, "cluster": model.labels_.astype("int64"),
                         "distance_to_centroid": distance})
    out = pd.DataFrame({"task_id": full.index}).merge(done, on="task_id", how="left")
    out["cluster"] = out["cluster"].fillna(-1).astype("int64")
    return ClusterResult(out.sort_values("task_id", kind="stable").reset_index(drop=True), k, scores[k], scores,
                         matrix, left_out)
