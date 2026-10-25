"""The answer key as the evaluation sees it: stage-A diseases to find, and decoys that look sick but are healthy."""
from __future__ import annotations

import csv
import json
from dataclasses import dataclass
from pathlib import Path

DECOY_KINDS = frozenset({"slow_legit", "backlog", "abandoned"})


@dataclass(frozen=True)
class Disease:
    id: str
    type: str                   # chronic_blocking / recurring_blocker / slow_chain / ...
    severity: str               # subtle / moderate / severe
    in_stage_a: bool
    root_task_id: str
    task_ids: frozenset[str]
    edges: frozenset[str]       # "task_id>depends_on_task_id", the same spelling the detectors use


@dataclass(frozen=True)
class Truth:
    diseases: tuple[Disease, ...]
    decoy_tasks: frozenset[str]


def load_truth(data_dir: Path | str) -> Truth:
    d = Path(data_dir)
    raw = json.loads((d / "ground_truth.json").read_text(encoding="utf-8"))["diseases"]
    diseases = tuple(
        Disease(id=x["id"], type=x["type"], severity=x["severity"], in_stage_a=bool(x["in_stage_a"]),
                root_task_id=x["root_task_id"], task_ids=frozenset(x["task_ids"]),
                edges=frozenset(f"{e['task_id']}>{e['depends_on_task_id']}" for e in x["observed"].get("edges", [])))
        for x in raw)
    with (d / "answer_key_tasks.csv").open(encoding="utf-8", newline="") as f:
        decoys = frozenset(r["task_id"] for r in csv.DictReader(f) if r["kind"] in DECOY_KINDS)
    return Truth(diseases, decoys)
