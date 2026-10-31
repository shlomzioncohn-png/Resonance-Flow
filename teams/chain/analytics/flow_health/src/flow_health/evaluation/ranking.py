"""The union of the three detectors as one ranked list. A recommendation only: nothing here writes anywhere but a CSV."""
from __future__ import annotations

import csv
from pathlib import Path

from ..detectors.common import Flag, rank_order
from .matching import flag_tasks

COLUMNS = ["rank", "pattern", "entity", "severity", "score", "explanation", "related", "flag_id"]


def rank_flags(flags: list[Flag]) -> list[dict]:
    """Severity (high, medium, low), then score descending; ties by flag_id so runs agree.
    `related`: ranks of the rows from another detector that share a task with this one."""
    ordered = rank_order(flags)
    rows = [{"rank": i, "pattern": f.pattern, "entity": ";".join(f.entity_ids), "severity": f.severity,
             "score": f.score, "explanation": f.explanation, "related": "", "flag_id": f.flag_id}
            for i, f in enumerate(ordered, start=1)]
    tasks = [flag_tasks(f) for f in ordered]
    for i, row in enumerate(rows):
        row["related"] = ";".join(str(rows[j]["rank"]) for j in range(len(rows))
                                  if j != i and ordered[j].pattern != ordered[i].pattern and tasks[i] & tasks[j])
    return rows


def write_ranked(rows: list[dict], path: Path | str) -> None:
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
