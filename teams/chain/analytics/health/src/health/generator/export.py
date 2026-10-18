from __future__ import annotations

import csv
import json
from collections import defaultdict
from dataclasses import asdict, fields
from datetime import datetime
from pathlib import Path
from uuid import UUID

from .config import DISEASE_TYPES, GeneratorConfig
from .model import World
from .simulation import History


def _cell(value) -> str:
    if value is None:
        return ""  # CSV NULL, as Postgres COPY expects
    if isinstance(value, datetime):
        return value.isoformat(timespec="microseconds")
    return str(value)


def _write_csv(path: Path, columns: list[str], rows: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(columns)
        for row in rows:
            writer.writerow([_cell(row[c]) for c in columns])


def _json_default(o):
    if isinstance(o, (UUID, datetime)):
        return _cell(o)
    raise TypeError(type(o))


def _observed(cfg: GeneratorConfig, world: World, history: History) -> dict:
    """What each planted disease actually looked like once played out (some end up truncated or ongoing)."""
    opened: dict = {}
    longest: dict = defaultdict(float)
    root_done: dict = defaultdict(int)
    for r in history.state_rows:
        if r.event_type == "BLOCKED":
            opened[r.task_id] = r.created_at
        elif r.event_type == "UNBLOCKED":
            days = (r.created_at - opened.pop(r.task_id)).total_seconds() / 86400
            longest[r.task_id] = max(longest[r.task_id], days)
        elif r.to_status == "DONE":
            root_done[r.task_id] += 1
    still_blocked = set(opened)
    for task, since in opened.items():
        longest[task] = max(longest[task], (cfg.end - since).total_seconds() / 86400)
    out = {}
    for d in world.diseases:
        members = set(d.task_ids)
        out[d.id] = {
            "ongoing_at_end": history.final_status[d.root_task_id] != "DONE" or bool(members & still_blocked),
            "tasks_blocked_at_end": len(members & still_blocked),
            "longest_block_days": round(max(longest[t] for t in members), 1),
            "root_times_done": root_done[d.root_task_id],
        }
    return out


def write_dataset(out_dir: Path, cfg: GeneratorConfig, world: World, history: History) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)

    for name, rows in (("dependency_history", history.dependency_rows), ("task_state_history", history.state_rows)):
        if not rows:
            raise ValueError(f"{name} is empty")
        _write_csv(out_dir / f"{name}.csv", [f.name for f in fields(rows[0])], [asdict(r) for r in rows])

    # generator-only side files: they describe the answer key and must never feed the model
    projects = {p.id: p for p in world.projects}
    _write_csv(
        out_dir / "tasks_meta.csv",
        ["task_id", "project", "archetype", "created_at", "kind", "disease_id"],
        [{"task_id": t.id, "project": t.project, "archetype": projects[t.project].archetype,
          "created_at": t.created_at, "kind": t.kind, "disease_id": t.disease_id} for t in world.tasks])

    sick = {tid for d in world.diseases for tid in d.task_ids}
    manifest = {
        "seed": cfg.seed,
        "history_start": cfg.start,
        "as_of": cfg.end,  # "now" for the analysis: open blocks are measured up to here
        "counts": {
            "tasks": len(world.tasks),
            "dependency_history_rows": len(history.dependency_rows),
            "task_state_history_rows": len(history.state_rows),
            "planted_diseases": len(world.diseases),
            "sick_tasks": len(sick),
            "rejected_rows": sum(1 for r in history.dependency_rows if r.action == "REJECTED"),
        },
        "disease_types_planted": sorted({d.type for d in world.diseases}),
        "disease_types_held_out": [t for t in DISEASE_TYPES if t in cfg.excluded_diseases],
        "config": {k: (v if not isinstance(v, datetime) else _cell(v)) for k, v in asdict(cfg).items()},
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, default=_json_default, indent=2), encoding="utf-8")
    observed = _observed(cfg, world, history)
    (out_dir / "ground_truth.json").write_text(
        json.dumps({"diseases": [{**asdict(d), "observed": observed[d.id]} for d in world.diseases]},
                   default=_json_default, indent=2),
        encoding="utf-8")
    # flat form for evaluation: one row per sick task
    _write_csv(
        out_dir / "ground_truth.csv",
        ["task_id", "disease_id", "type", "severity", "role", "ongoing_at_end"],
        [{"task_id": tid, "disease_id": d.id, "type": d.type, "severity": d.severity,
          "role": "root" if tid == d.root_task_id else "member",
          "ongoing_at_end": str(observed[d.id]["ongoing_at_end"]).lower()}
         for d in world.diseases for tid in d.task_ids])
    return manifest
