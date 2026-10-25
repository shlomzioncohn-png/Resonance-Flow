from __future__ import annotations

import pandas as pd

from .kmeans import CLUSTER_FEATURES, RANDOM_STATE, ClusterResult

DOMINANT_TIER_SHARE = 0.8  # a cluster this dominated by one tier looks like a split by tier
MIN_DOMINATED_SIZE = 30


def render_report(result: ClusterResult, features: pd.DataFrame) -> str:
    tiers = features.set_index("task_id")["tier"]
    assigned = result.assignments.set_index("task_id")["cluster"]
    clustered = assigned[assigned >= 0]
    medians = result.matrix.groupby(clustered).median()
    tier_counts = pd.crosstab(clustered, tiers.reindex(clustered.index))
    shares = tier_counts.div(tier_counts.sum(axis=1), axis=0)
    sizes = clustered.value_counts().sort_index()
    left_out = f" ({result.n_left_out} left out because a cluster feature is unknown)" if result.n_left_out else ""

    lines = [
        "# Cluster report",
        "",
        "One KMeans clustering of the tasks, to describe normal behaviour. It flags nothing.",
        "",
        f"- Tasks clustered: {len(clustered)} of {len(assigned)}{left_out}",
        f"- Cluster features (standardised): {', '.join(f'`{c}`' for c in CLUSTER_FEATURES)}",
        "- `tier` and `project_id` are not used for clustering",
        f"- Chosen k: **{result.k}** (best silhouette for k in {min(result.silhouette_by_k)}-"
        f"{max(result.silhouette_by_k)}), random_state {RANDOM_STATE}",
        f"- Overall silhouette: **{result.silhouette:.3f}**",
        "",
        "## Silhouette by k",
        "",
        "| k | silhouette |",
        "|---|---|",
        *[f"| {k}{' (chosen)' if k == result.k else ''} | {s:.3f} |" for k, s in result.silhouette_by_k.items()],
        "",
        "## Clusters",
        "",
        "Size and median of each cluster feature, in original units.",
        "",
        "| cluster | tasks | " + " | ".join(CLUSTER_FEATURES) + " |",
        "|---|---|" + "---|" * len(CLUSTER_FEATURES),
        *[f"| {c} | {sizes[c]} | " + " | ".join(f"{medians.loc[c, f]:.3g}" for f in CLUSTER_FEATURES) + " |"
          for c in sizes.index],
        "",
        "## Tier mix inside each cluster",
        "",
        "Share of each cluster's tasks per tier. A cluster that is almost all one tier would mean the clustering "
        "split by tier, which the ratios to the tier median are meant to prevent.",
        "",
        "| cluster | " + " | ".join(shares.columns) + " |",
        "|---|" + "---|" * len(shares.columns),
        *[f"| {c} | " + " | ".join(f"{shares.loc[c, t]:.0%}" for t in shares.columns) + " |" for c in sizes.index],
    ]
    dominated = [str(c) for c in sizes.index if shares.loc[c].max() >= DOMINANT_TIER_SHARE and sizes[c] >= MIN_DOMINATED_SIZE]
    lines += ["", f"Clusters with {DOMINANT_TIER_SHARE:.0%} or more of one tier (at least {MIN_DOMINATED_SIZE} tasks): "
              + (", ".join(dominated) if dominated else "none"), ""]
    return "\n".join(lines)
