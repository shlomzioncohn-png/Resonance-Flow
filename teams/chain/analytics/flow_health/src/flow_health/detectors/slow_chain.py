"""Detector 3: a stretch of the dependency graph whose tasks are all worked on far longer than usual, with the delay
spread over its links (one dominant link is a chronic blocker, not a slow chain)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .common import Flag, make_flag_id, percentile_scores
from .config import SlowChainConfig

PATTERN = "SLOW_CHAIN"
_MAD_TO_SIGMA = 0.6745


def _severity(ratio: float, cfg: SlowChainConfig) -> str:
    if ratio >= cfg.high_from_ratio:
        return "high"
    return "medium" if ratio >= cfg.medium_from_ratio else "low"


def _robust_z(values: pd.Series) -> tuple[pd.Series, float]:
    """0.6745 * (x - median) / MAD. When more than half the values are identical (MAD = 0) the IQR stands in for the
    spread; a group with no spread at all gets no z (NaN) and so no flags."""
    median = values.median()
    mad = (values - median).abs().median()
    if mad > 0:
        return _MAD_TO_SIGMA * (values - median) / mad, median
    iqr = values.quantile(0.75) - values.quantile(0.25)
    if iqr > 0:
        return (values - median) / (iqr / 1.349), median
    return pd.Series(np.nan, index=values.index), median


def _drop_overlaps(candidates: list[tuple], max_overlap: float) -> list[tuple]:
    """Windows of one slow stretch overlap. Strongest first; a window is dropped when more than `max_overlap` of the
    smaller of the two is shared with one already kept."""
    kept: list[tuple] = []
    holders: dict[str, list[int]] = {}
    for cand in sorted(candidates, key=lambda c: (-c[1], c[0])):
        tasks = set(cand[3])
        shared: dict[int, int] = {}
        for task in tasks:
            for k in holders.get(task, ()):
                shared[k] = shared.get(k, 0) + 1
        if any(n / min(len(tasks), len(kept[k][3])) > max_overlap for k, n in shared.items()):
            continue
        kept.append(cand)
        for task in tasks:
            holders.setdefault(task, []).append(len(kept) - 1)
    return kept


def detect(paths: pd.DataFrame, tasks: pd.DataFrame, cfg: SlowChainConfig) -> list[Flag]:
    """`paths` comes from chain_paths (task_ids root first, chain_length, end_to_end_days, max_hop_share); `tasks` is
    features_task. A path's value is the median, over its tasks, of `cfg.link_ratio_column` (work time against the
    usual of the task's tier). It is compared only with paths of the same length and flagged when the value is at
    least `min_ratio_to_median` group medians and a robust z of `min_robust_z`, and no link took more than
    `max_link_share` of the total time."""
    if paths.empty:
        return []
    link_ratio = tasks.set_index("task_id")[cfg.link_ratio_column]
    candidates = []
    for length, group in paths[paths["chain_length"].between(cfg.min_length, cfg.max_length)].groupby("chain_length"):
        if len(group) < cfg.min_group_size:
            continue
        value = group["task_ids"].map(lambda ids: float(np.nanmedian(link_ratio.reindex(ids.split(";")).to_numpy())))
        z, median = _robust_z(value)
        if not median > 0:
            continue
        ratio = value / median
        hit = (ratio >= cfg.min_ratio_to_median) & (z >= cfg.min_robust_z) & (group["max_hop_share"] <= cfg.max_link_share)
        for r in group[hit].itertuples():
            ids = r.task_ids.split(";")
            candidates.append((make_flag_id(PATTERN, ids), float(z[r.Index]), float(ratio[r.Index]), ids, float(value[r.Index]),
                               float(median), int(length), r))

    kept = sorted(_drop_overlaps(candidates, cfg.max_overlap), key=lambda c: c[0])
    scores = percentile_scores([c[1] for c in kept], [c[0] for c in kept])
    scope = " בפרויקט" if cfg.link_ratio_column.endswith("_project") else ""
    flags = []
    for (fid, z, ratio, ids, value, median, length, r), score in zip(kept, scores):
        text = (f"שרשרת באורך {length} נסגרה ב-{round(r.end_to_end_days)} יום; חציון זמן העבודה של חוליה הוא פי {value:.1f} "
                f"מהרגיל ב-tier שלה{scope} (פי {ratio:.1f} מהחציון של שרשראות באורך {length}), "
                f"ללא חוליה שאחראית ליותר מ-{round(cfg.max_link_share * 100)} אחוז מהזמן "
                f"(הגדולה ביותר: {round(r.max_hop_share * 100)} אחוז)")
        evidence = {"chain_length": length, "end_to_end_days": round(float(r.end_to_end_days), 2),
                    "median_link_ratio": round(value, 2), "group_median_link_ratio": round(median, 2),
                    "ratio_to_median": round(ratio, 2), "robust_z": round(z, 2),
                    "max_link_share": round(float(r.max_hop_share), 3), "sink_task_id": r.sink_task_id}
        flags.append(Flag(flag_id=fid, pattern=PATTERN, entity_type="chain", entity_ids=ids, score=score,
                          severity=_severity(ratio, cfg), explanation=text, evidence=evidence))
    return flags
