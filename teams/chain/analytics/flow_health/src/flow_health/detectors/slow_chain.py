"""Detector 3: a dependency chain that takes several times longer than chains of the same length, with the delay
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


def detect(chains: pd.DataFrame, cfg: SlowChainConfig) -> list[Flag]:
    """`chains` is features_chain: task_ids (root first, joined by ';'), chain_length, end_to_end_days, max_hop_share."""
    pool = chains[chains["chain_length"].between(cfg.min_length, cfg.max_length)]
    rows = []
    for length, group in pool.groupby("chain_length", sort=True):
        if len(group) < cfg.min_group_size:
            continue
        z, median = _robust_z(group["end_to_end_days"])
        if median <= 0:
            continue
        ratio = group["end_to_end_days"] / median
        hit = group[(ratio >= cfg.min_ratio_to_median) & (z >= cfg.min_robust_z)
                    & (group["max_hop_share"] <= cfg.max_link_share)]
        for r in hit.itertuples():
            rows.append((r, int(length), float(median), float(ratio[r.Index]), float(z[r.Index])))

    built = []
    for r, length, median, ratio, z in rows:
        ids = r.task_ids.split(";")
        text = (f"זמן סגירה {round(r.end_to_end_days)} יום, פי {ratio:.1f} מהחציון של שרשראות באורך {length}, "
                f"ללא חוליה שאחראית ליותר מ-{round(cfg.max_link_share * 100)} אחוז "
                f"(החוליה הגדולה ביותר: {round(r.max_hop_share * 100)} אחוז)")
        evidence = {"end_to_end_days": round(float(r.end_to_end_days), 2), "chain_length": length,
                    "group_median_days": round(median, 2), "ratio_to_median": round(ratio, 2),
                    "robust_z": round(z, 2), "max_link_share": round(float(r.max_hop_share), 3),
                    "sink_task_id": r.sink_task_id}
        built.append((make_flag_id(PATTERN, ids), z, ratio, ids, text, evidence))

    built = list({b[0]: b for b in reversed(built)}.values())  # the same task set reached from two sinks: one flag
    built.sort(key=lambda b: b[0])
    scores = percentile_scores([b[1] for b in built], [b[0] for b in built])
    return [Flag(flag_id=fid, pattern=PATTERN, entity_type="chain", entity_ids=ids, score=score,
                 severity=_severity(ratio, cfg), explanation=text, evidence=ev)
            for (fid, _, ratio, ids, text, ev), score in zip(built, scores)]
