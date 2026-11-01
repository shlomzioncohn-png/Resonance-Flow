"""One command: features -> detectors -> evaluation on one data set, with the frozen-config guard.

    python -m flow_health.run_all --data data/heldout
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

from .detectors import __main__ as detectors_cli
from .detectors.common import Flag
from .detectors.config import DEFAULT_PATH
from .evaluation import mentor
from .evaluation.metrics import evaluate
from .evaluation.ranking import rank_flags, write_ranked
from .evaluation.report import render_report
from .evaluation.truth import load_truth
from .features import __main__ as features_cli

LOCK = Path(__file__).with_name("evaluation") / "config.lock"


def config_hash(path: Path) -> str:
    """SHA-256 of the file with line endings normalised, so Windows and Linux checkouts agree."""
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m flow_health.run_all",
                                description="Features, detectors and evaluation on one data set (recommendations only).")
    p.add_argument("--data", type=Path, required=True, help="the generator's CSV directory, e.g. data/heldout")
    p.add_argument("--out", type=Path, default=None, help="output directory (default: out/<name of --data>)")
    p.add_argument("--config", type=Path, default=DEFAULT_PATH, help="detector thresholds (default: detectors/config.yaml)")
    p.add_argument("--freeze", action="store_true", help="lock the hash of the config; later runs refuse any other config")
    p.add_argument("--tenant", default=None,
                   help="uuid of the tenant the data belongs to; recorded in flags.json so `flow_health.publish` can load it")
    args = p.parse_args(argv)

    digest = config_hash(args.config)
    if args.freeze:
        LOCK.write_text(digest + "\n", encoding="utf-8", newline="\n")
        print(f"config frozen: {digest}")
    frozen = LOCK.is_file()
    if frozen and LOCK.read_text(encoding="utf-8").strip() != digest:
        print(f"refusing to run: {args.config} (sha256 {digest}) differs from the frozen config "
              f"({LOCK.read_text(encoding='utf-8').strip()}). Thresholds are not tuned after freezing.", file=sys.stderr)
        return 2

    out = args.out or Path("out") / args.data.name
    seed = json.loads((args.data / "manifest.json").read_text(encoding="utf-8")).get("seed")
    features_cli.main(["--data", str(args.data), "--out", str(out)])
    detectors_args = ["--data", str(args.data), "--features", str(out), "--config", str(args.config),
                      "--seed", str(seed)]
    if args.tenant:
        detectors_args += ["--tenant", args.tenant]
    detectors_cli.main(detectors_args)

    payload = json.loads((out / "flags.json").read_text(encoding="utf-8"))
    flags = [Flag(**f) for f in payload["flags"]]
    ev = evaluate(flags, load_truth(args.data))
    meta = {"data": str(args.data), "seed": seed, "as_of": payload["meta"].get("as_of"),
            "config_sha256": digest, "config_frozen": frozen}

    write_ranked(rank_flags(flags), out / "ranked_flags.csv")
    sheet = out / "mentor_review.csv"
    wrote = mentor.write_sample(flags, sheet)
    if not wrote:
        print(f"{sheet} already has the mentor's answers: left as it is")
    summary = mentor.summarize(sheet)
    (out / "evaluation.json").write_text(
        json.dumps({"meta": meta, **ev, "mentor": summary}, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8", newline="\n")
    (out / "evaluation_report.md").write_text(render_report(ev, meta, summary), encoding="utf-8", newline="\n")

    o = ev["overall"]
    print(f"{ev['n_flags']} flags: TP {o['tp']} FP {o['fp']} FN {o['fn']}, precision {o['precision']}, "
          f"recall {o['recall']} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
