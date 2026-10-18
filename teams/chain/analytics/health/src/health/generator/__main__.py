from __future__ import annotations

import argparse
import sys
from dataclasses import replace
from pathlib import Path

from . import GeneratorConfig, generate, validate
from .export import write_dataset


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m health.generator",
                                description="Generate synthetic dependency/state history for the health analysis.")
    p.add_argument("--out", type=Path, default=Path("out/dev"), help="output directory (default: out/dev)")
    p.add_argument("--seed", type=int, default=GeneratorConfig.seed)
    p.add_argument("--tasks", type=int, default=GeneratorConfig.n_normal_tasks, help="healthy-process tasks")
    p.add_argument("--days", type=int, default=GeneratorConfig.history_days)
    p.add_argument("--projects", type=int, default=GeneratorConfig.n_projects)
    p.add_argument("--instances", type=int, default=GeneratorConfig.instances_per_severity,
                   help="planted instances per disease type and severity")
    p.add_argument("--include-holdout", action="store_true",
                   help="also plant the held-out disease type(s); use only for the final evaluation set")
    args = p.parse_args(argv)

    cfg = replace(GeneratorConfig(), seed=args.seed, n_normal_tasks=args.tasks, history_days=args.days,
                  n_projects=args.projects, instances_per_severity=args.instances,
                  excluded_diseases=() if args.include_holdout else GeneratorConfig.excluded_diseases)
    data = generate(cfg)
    errors = validate(data.history.dependency_rows, data.history.state_rows)
    if errors:
        print("generated history is inconsistent:", *errors, sep="\n  ", file=sys.stderr)
        return 1
    manifest = write_dataset(args.out, cfg, data.world, data.history)
    c = manifest["counts"]
    print(f"wrote {args.out}: {c['tasks']} tasks, {c['dependency_history_rows']} dependency rows, "
          f"{c['task_state_history_rows']} state rows, {c['planted_diseases']} planted diseases "
          f"({c['sick_tasks']} sick tasks); held out: {manifest['disease_types_held_out'] or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
