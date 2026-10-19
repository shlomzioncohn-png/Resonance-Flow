from __future__ import annotations

import argparse
import sys
from dataclasses import replace
from pathlib import Path

from . import GeneratorConfig, generate, validate
from .export import write_dataset

DEFAULT_EVAL_SEED = 4242


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="python -m health.generator",
        description="Generate two independent synthetic histories: dev (to build the model on, held-out "
                    "disease type excluded) and eval (different seed, every disease type, to measure it).")
    p.add_argument("--out", type=Path, default=Path("out"), help="writes <out>/dev and <out>/eval (default: out)")
    p.add_argument("--seed", type=int, default=GeneratorConfig.seed, help="seed of the dev set")
    p.add_argument("--eval-seed", type=int, default=DEFAULT_EVAL_SEED, help="seed of the eval set")
    p.add_argument("--tasks", type=int, default=GeneratorConfig.n_normal_tasks, help="healthy-process tasks")
    p.add_argument("--days", type=int, default=GeneratorConfig.history_days)
    p.add_argument("--projects", type=int, default=GeneratorConfig.n_projects)
    p.add_argument("--instances", type=int, default=GeneratorConfig.instances_per_severity,
                   help="planted instances per disease type and severity")
    args = p.parse_args(argv)
    if args.seed == args.eval_seed:
        p.error("--seed and --eval-seed must differ, otherwise eval shares its healthy data with dev")

    base = replace(GeneratorConfig(), n_normal_tasks=args.tasks, history_days=args.days,
                   n_projects=args.projects, instances_per_severity=args.instances)
    datasets = (("dev", replace(base, seed=args.seed)),  # held-out type stays excluded
                ("eval", replace(base, seed=args.eval_seed, excluded_diseases=())))
    for name, cfg in datasets:
        data = generate(cfg)
        errors = validate(data.history.dependency_rows, data.history.state_rows)
        if errors:
            print(f"{name}: generated history is inconsistent:", *errors, sep="\n  ", file=sys.stderr)
            return 1
        manifest = write_dataset(args.out / name, cfg, data.world, data.history)
        c = manifest["counts"]
        print(f"{name} (seed {cfg.seed}) -> {args.out / name}: {c['tasks']} tasks, "
              f"{c['dependency_history_rows']} dependency rows, {c['task_state_history_rows']} state rows, "
              f"{c['planted_diseases']} planted diseases ({c['sick_tasks']} sick tasks); "
              f"held out: {manifest['disease_types_held_out'] or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
