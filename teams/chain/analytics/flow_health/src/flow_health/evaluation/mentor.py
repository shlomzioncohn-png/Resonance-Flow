"""Face-validity sample for the mentor: a fixed-seed random draw per detector, with no hint of right or wrong."""
from __future__ import annotations

import csv
import random
from pathlib import Path

from ..detectors.common import PATTERNS, Flag

COLUMNS = ["flag_id", "pattern", "explanation", "reasonable", "comment"]
PER_DETECTOR = 5
SEED = 7
_YES = ("yes", "y")


def sample_flags(flags: list[Flag], per_detector: int = PER_DETECTOR, seed: int = SEED) -> list[Flag]:
    """Up to `per_detector` flags of each pattern (all of them when there are fewer), whatever their outcome."""
    rng = random.Random(seed)
    picked: list[Flag] = []
    for pattern in PATTERNS:
        pool = sorted((f for f in flags if f.pattern == pattern), key=lambda f: f.flag_id)
        picked += sorted(rng.sample(pool, min(per_detector, len(pool))), key=lambda f: f.flag_id)
    return picked


def _read(path: Path) -> list[dict]:
    with path.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def is_filled(path: Path | str) -> bool:
    p = Path(path)
    return p.is_file() and any((r.get("reasonable") or "").strip() for r in _read(p))


def write_sample(flags: list[Flag], path: Path | str) -> bool:
    """Writes the sheet unless the mentor already answered in it. Returns whether it wrote."""
    if is_filled(path):
        return False
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8-sig", newline="") as f:     # BOM so Excel opens it as UTF-8
        w = csv.writer(f, lineterminator="\n")
        w.writerow(COLUMNS)
        for fl in sample_flags(flags):
            w.writerow([fl.flag_id, fl.pattern, fl.explanation, "", ""])
    return True


def summarize(path: Path | str) -> dict | None:
    """Counts and comments of a filled sheet; None while the mentor has not answered."""
    if not is_filled(path):
        return None
    rows = _read(Path(path))
    answered = [r for r in rows if r["reasonable"].strip()]
    return {"answered": len(answered), "total": len(rows),
            "reasonable": sum(r["reasonable"].strip().lower() in _YES for r in rows),
            "comments": [{"flag_id": r["flag_id"], "pattern": r["pattern"], "reasonable": r["reasonable"].strip(),
                          "comment": r["comment"].strip()} for r in rows if r["comment"].strip()]}
