"""Detector 2: a dependency edge that is added and removed again and again (a blocker cycle that keeps coming back)."""
from __future__ import annotations

import statistics
from collections.abc import Mapping

import pandas as pd

from .common import Flag, make_flag_id, percentile_scores
from .config import RecurringCycleConfig

PATTERN = "RECURRING_CYCLE"
_DAY = 86400.0


def _edge_id(task_id: str, depends_on: str) -> str:
    return f"{task_id}>{depends_on}"


def _gap_stats(times: list[pd.Timestamp]) -> dict:
    gaps = [(b - a).total_seconds() / _DAY for a, b in zip(times, times[1:])]
    mean = statistics.fmean(gaps)
    return {"gaps": gaps, "median_gap_days": statistics.median(gaps),
            "cv": statistics.pstdev(gaps) / mean if mean > 0 else 0.0,
            "span_days": (times[-1] - times[0]).total_seconds() / _DAY}


def _severity(transitions: int, cv: float, cfg: RecurringCycleConfig) -> str:
    if transitions >= cfg.high_min_transitions or (transitions >= cfg.medium_min_transitions and cv <= cfg.tight_cv):
        return "high"
    return "medium" if transitions >= cfg.medium_min_transitions else "low"


def _fmt(days: float) -> str:
    return f"{days:.1f}".removesuffix(".0")


def _edge_histories(deps: pd.DataFrame, done_at: Mapping[str, pd.Timestamp]) -> dict[tuple[str, str], list[tuple[pd.Timestamp, str]]]:
    """Events of each edge up to the moment its target finished: an edge that is put back on a task that is already
    DONE blocks nothing, so those events are not part of a blocker that keeps coming back."""
    ordered = deps.sort_values(["created_at", "sequence"], kind="stable")
    histories = {}
    for key, g in ordered.groupby(["task_id", "depends_on_task_id"], sort=True):
        finished = done_at.get(key[1])
        events = [(at, action) for at, action in zip(g["created_at"], g["action"]) if finished is None or at < finished]
        if events:
            histories[key] = events
    return histories


def _active_at(history: list[tuple[pd.Timestamp, str]], moment: pd.Timestamp) -> bool:
    active = False
    for at, action in history:
        if at > moment:
            break
        active = action == "ADDED"
    return active


def _edge_flags(histories, cfg, slow_blocker) -> list[tuple[float, Flag]]:
    out = []
    for (task, target), events in histories.items():
        transitions = len(events) - 1  # the first ADDED only creates the edge
        if transitions < cfg.min_transitions or not slow_blocker(target):
            continue
        stats = _gap_stats([at for at, _ in events])
        if not cfg.min_median_gap_days <= stats["median_gap_days"] <= cfg.max_median_gap_days:
            continue
        entity = [_edge_id(task, target)]
        evidence = {"transitions": transitions, "span_days": round(stats["span_days"], 2),
                    "median_gap_days": round(stats["median_gap_days"], 2), "gap_cv": round(stats["cv"], 2),
                    "gaps_days": [round(g, 2) for g in stats["gaps"]], "last_action": events[-1][1]}
        text = (f"The edge switched {transitions} times within {round(stats['span_days'])} days "
                f"(median gap {_fmt(stats['median_gap_days'])} days)")
        flag = Flag(flag_id=make_flag_id(PATTERN, entity), pattern=PATTERN, entity_type="edge", entity_ids=entity,
                    score=0.0, severity=_severity(transitions, stats["cv"], cfg), explanation=text, evidence=evidence)
        out.append((transitions + 1 / (1 + stats["cv"]), flag))  # ties between equal counts: the more even gaps rank higher
    return out


def _pair_flags(histories, flagged: set[tuple[str, str]], cfg, slow_blocker) -> list[tuple[float, Flag]]:
    """Near-cycle: two edges of one task were live together, one was removed and came back after a while.
    Edges already flagged on their own are left out, so one problem is not reported twice."""
    by_task: dict[str, list[tuple[str, str]]] = {}
    for key in histories:
        by_task.setdefault(key[0], []).append(key)
    out = []
    for task, keys in by_task.items():
        for broken in keys:
            events = histories[broken]
            if broken in flagged or len(events) < 3 or not slow_blocker(broken[1]):
                continue
            for i in range(len(events) - 2):
                (t_add, a1), (t_rem, a2), (t_back, a3) = events[i:i + 3]
                if (a1, a2, a3) != ("ADDED", "REMOVED", "ADDED"):
                    continue
                away = (t_back - t_rem).total_seconds() / _DAY
                if away < cfg.pair_min_return_days:
                    continue
                for other in keys:
                    if other == broken or not (_active_at(histories[other], t_rem) and _active_at(histories[other], t_back)):
                        continue
                    ids = sorted([_edge_id(*broken), _edge_id(*other)])
                    gaps = _gap_stats([t_add, t_rem, t_back])
                    out.append((2 + 1 / (1 + gaps["cv"]), Flag(
                        flag_id=make_flag_id(PATTERN, ids), pattern=PATTERN, entity_type="edge_pair", entity_ids=ids,
                        score=0.0, severity="low",
                        explanation=(f"Two edges of one task: one was removed and came back after {_fmt(away)} days, "
                                     f"while the other stayed active"),
                        evidence={"broken_edge": _edge_id(*broken), "kept_edge": _edge_id(*other),
                                  "removed_at": t_rem.isoformat(), "returned_at": t_back.isoformat(),
                                  "days_away": round(away, 2)})))
    return out


def detect(dependency_history: pd.DataFrame, cfg: RecurringCycleConfig,
           done_at: Mapping[str, pd.Timestamp] | None = None,
           blocker_ratio: Mapping[str, float] | None = None) -> list[Flag]:
    """`dependency_history`: ADDED / REMOVED rows with task_id, depends_on_task_id, action, created_at, sequence.
    `done_at`: when each finished task reached DONE; events on an edge after its target finished are ignored.
    `blocker_ratio`: task_id -> `active_ratio_to_tier_project`; an edge counts only when the task it points at is itself
    at least `min_blocker_active_ratio` (a blocker that stays stuck). None: no such condition."""
    if dependency_history.empty:
        return []
    histories = _edge_histories(dependency_history, done_at or {})

    def slow_blocker(task_id: str) -> bool:
        return blocker_ratio is None or blocker_ratio.get(task_id, 0.0) >= cfg.min_blocker_active_ratio

    edges = _edge_flags(histories, cfg, slow_blocker)
    flagged = {tuple(f.entity_ids[0].split(">")) for _, f in edges}
    found = edges + _pair_flags(histories, flagged, cfg, slow_blocker)
    found = list({f.flag_id: (v, f) for v, f in reversed(found)}.values())  # one flag per id (first occurrence wins)
    found.sort(key=lambda item: item[1].flag_id)
    scores = percentile_scores([v for v, _ in found], [f.flag_id for _, f in found])
    return [Flag(**{**f.__dict__, "score": s}) for (_, f), s in zip(found, scores)]
