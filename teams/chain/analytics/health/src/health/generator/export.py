from __future__ import annotations

import csv
import json
from collections import defaultdict
from dataclasses import asdict, fields
from datetime import datetime
from pathlib import Path
from uuid import UUID

from .config import GeneratorConfig
from .diseases import DISEASE_TYPES
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
            writer.writerow([_cell(row.get(c)) for c in columns])


def _json_default(o):
    if isinstance(o, (UUID, datetime)):
        return _cell(o)
    raise TypeError(type(o))


def _observed(cfg: GeneratorConfig, world: World, history: History) -> dict:
    """What each planted disease actually looked like once played out (some end up truncated or ongoing)."""
    opened: dict = {}
    longest: dict = defaultdict(float)
    root_done: dict = defaultdict(int)
    last_activity: dict = {}
    for r in history.state_rows:
        last_activity[r.task_id] = r.created_at
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
    edges_by_task: dict = defaultdict(list)
    for r in history.dependency_rows:
        if r.action == "ADDED":
            edges_by_task[r.task_id].append(r.depends_on_task_id)

    out = {}
    for d in world.diseases:
        members = set(d.task_ids)
        ongoing = history.final_status[d.root_task_id] != "DONE" or bool(members & still_blocked)
        activity = [last_activity[t] for t in members if t in last_activity]
        out[d.id] = {
            "ongoing_at_end": ongoing,
            "tasks_blocked_at_end": len(members & still_blocked),
            "longest_block_days": round(max(longest[t] for t in members), 1),
            "root_times_done": root_done[d.root_task_id],
            "window_start": d.start,
            # an unresolved pattern is still going on "now"; a resolved one ends with its last event
            "window_end": cfg.end if ongoing else max(activity, default=d.start),
            "edges": [{"task_id": t, "depends_on_task_id": dep}
                      for t in d.task_ids for dep in edges_by_task[t] if dep in members],
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
        ["task_id", "tier", "project", "archetype", "created_at", "kind", "disease_id"],
        [{"task_id": t.id, "tier": t.tier, "project": t.project, "archetype": projects[t.project].archetype,
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
    # flat form for evaluation: one row per involved task and per involved edge
    rows = []
    for d in world.diseases:
        o = observed[d.id]
        common = {"disease_id": d.id, "type": d.type, "severity": d.severity, "tier": d.tier,
                  "window_start": o["window_start"], "window_end": o["window_end"],
                  "ongoing_at_end": str(o["ongoing_at_end"]).lower()}
        rows += [{**common, "element_type": "task", "task_id": t,
                  "role": "root" if t == d.root_task_id else "member"} for t in d.task_ids]
        rows += [{**common, "element_type": "edge", **e} for e in o["edges"]]
    _write_csv(out_dir / "ground_truth.csv",
               ["disease_id", "type", "severity", "tier", "element_type", "task_id", "depends_on_task_id", "role",
                "window_start", "window_end", "ongoing_at_end"], rows)
    return manifest
