from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import pandas as pd

from ..loaders import CsvLoader
from . import chronic_blocker, recurring_cycle, slow_chain
from .common import PATTERNS, Flag, write_flags
from .config import DetectorConfig, load_config


def run_detectors(tasks: pd.DataFrame, dependency_history: pd.DataFrame, chains: pd.DataFrame,
                  cfg: DetectorConfig, clusters: pd.DataFrame | None = None) -> list[Flag]:
    """All three detectors, one after the other. Merging them into one ranked list is the next task."""
    return (chronic_blocker.detect(tasks, cfg.chronic_blocker, clusters)
            + recurring_cycle.detect(dependency_history, cfg.recurring_cycle)
            + slow_chain.detect(chains, cfg.slow_chain))


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m flow_health.detectors",
                                description="Run the three detectors and write flags.json (recommendations only).")
    p.add_argument("--data", type=Path, required=True, help="the generator's CSV directory, e.g. data/dev")
    p.add_argument("--features", type=Path, required=True,
                   help="output of `python -m flow_health.features`, e.g. out/dev")
    p.add_argument("--out", type=Path, default=None, help="flags file (default: <features>/flags.json)")
    p.add_argument("--config", type=Path, default=None, help="thresholds file (default: detectors/config.yaml)")
    p.add_argument("--seed", type=int, default=None, help="seed of the data set, recorded in the file for reference")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    ds = CsvLoader(args.data).load()
    tasks = pd.read_csv(args.features / "features_task.csv")
    chains = pd.read_csv(args.features / "features_chain.csv")
    cluster_file = args.features / "clusters_task.csv"
    clusters = pd.read_csv(cluster_file) if cluster_file.is_file() else None

    flags = run_detectors(tasks, ds.dependency_history, chains, load_config(args.config), clusters)
    out = args.out or args.features / "flags.json"
    write_flags(flags, out, {"seed": args.seed, "as_of": ds.as_of.isoformat(), "data": str(args.data),
                             "counts": {pattern: sum(f.pattern == pattern for f in flags) for pattern in PATTERNS}})
    print(f"{len(flags)} flags -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
