from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import pandas as pd

from ..features import build_timeline
from ..loaders import CsvLoader
from . import chronic_blocker, recurring_cycle, slow_chain
from .chain_paths import extract_paths, finish_times
from .common import PATTERNS, Flag, write_flags
from .config import DetectorConfig, load_config


def run_detectors(tasks: pd.DataFrame, dependency_history: pd.DataFrame, chains: pd.DataFrame,
                  cfg: DetectorConfig, clusters: pd.DataFrame | None = None,
                  done_at: dict[str, pd.Timestamp] | None = None) -> list[Flag]:
    """All three detectors, one after the other. Merging them into one ranked list is the next task."""
    blocker_ratio = tasks.set_index("task_id")[cfg.chronic_blocker.ratio_column].to_dict()
    return (chronic_blocker.detect(tasks, cfg.chronic_blocker, clusters)
            + recurring_cycle.detect(dependency_history, cfg.recurring_cycle, done_at, blocker_ratio)
            + slow_chain.detect(chains, tasks, cfg.slow_chain))


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m flow_health.detectors",
                                description="Run the three detectors and write flags.json (recommendations only).")
    p.add_argument("--data", type=Path, required=True, help="the generator's CSV directory, e.g. data/dev")
    p.add_argument("--features", type=Path, required=True,
                   help="output of `python -m flow_health.features` (task features, clusters), e.g. out/dev")
    p.add_argument("--out", type=Path, default=None, help="flags file (default: <features>/flags.json)")
    p.add_argument("--config", type=Path, default=None, help="thresholds file (default: detectors/config.yaml)")
    p.add_argument("--seed", type=int, default=None, help="seed of the data set, recorded in the file for reference")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    ds = CsvLoader(args.data).load()
    tasks = pd.read_csv(args.features / "features_task.csv")
    cluster_file = args.features / "clusters_task.csv"
    clusters = pd.read_csv(cluster_file) if cluster_file.is_file() else None

    cfg = load_config(args.config)
    timeline = build_timeline(ds)
    chains = extract_paths(timeline, cfg.slow_chain)
    flags = run_detectors(tasks, ds.dependency_history, chains, cfg, clusters, finish_times(timeline))
    out = args.out or args.features / "flags.json"
    write_flags(flags, out, {"seed": args.seed, "as_of": ds.as_of.isoformat(), "data": str(args.data),
                             "counts": {pattern: sum(f.pattern == pattern for f in flags) for pattern in PATTERNS}})
    print(f"{len(flags)} flags -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
