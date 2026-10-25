from __future__ import annotations

import argparse
import logging
import shutil
import sys
from datetime import datetime
from pathlib import Path

from ..clustering import cluster_tasks, render_report
from ..loaders import CsvLoader
from . import build_timeline, chain_features, edge_features, task_features

SPEC = Path(__file__).with_name("feature_spec.md")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="python -m flow_health.features",
        description="Rebuild the timeline, compute task / edge / chain features and cluster the tasks.")
    p.add_argument("--data", type=Path, required=True, help="directory with the generator's CSV files, e.g. data/dev")
    p.add_argument("--out", type=Path, required=True, help="output directory, e.g. out/dev")
    p.add_argument("--as-of", type=datetime.fromisoformat, default=None,
                   help="end of the observation window, ISO-8601 (default: the latest event in the data)")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    ds = CsvLoader(args.data).load(as_of=args.as_of)
    timeline = build_timeline(ds)
    tasks, edges, chains = task_features(ds, timeline), edge_features(ds, timeline), chain_features(ds, timeline)
    clusters = cluster_tasks(tasks)

    args.out.mkdir(parents=True, exist_ok=True)
    for name, frame in (("features_task", tasks), ("features_edge", edges), ("features_chain", chains),
                        ("clusters_task", clusters.assignments)):
        frame.to_csv(args.out / f"{name}.csv", index=False, lineterminator="\n")
    (args.out / "cluster_report.md").write_text(render_report(clusters, tasks), encoding="utf-8", newline="\n")
    shutil.copyfile(SPEC, args.out / "feature_spec.md")
    print(f"as_of {ds.as_of.isoformat()}: {len(tasks)} tasks, {len(edges)} edges, {len(chains)} chains; "
          f"k={clusters.k}, silhouette {clusters.silhouette:.3f}; {len(timeline.warnings)} timeline warnings -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
