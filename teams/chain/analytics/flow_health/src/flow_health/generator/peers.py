"""Peer groups for the ground truth: what "similar tasks" and "similar chains" look like in this dataset.

A chronic blocker is judged against healthy blockers of the same tier, similar subtree size and the same
team pace; a slow chain against healthy chains of the same length, shape, tier and team pace. When a group
is too small, the comparison widens step by step instead of failing. When the task has no (known) tier,
the tier is simply left out of the comparison. The level that was used is reported with the statistics.
"""
from __future__ import annotations

import random
from bisect import bisect_right
from collections import defaultdict

from .config import GeneratorConfig
from .model import World
from .simulation import History

UNKNOWN_TIER = "unknown"
ANY = "*"  # "this dimension is not used" in a group key
MIN_BLOCKER_PEERS = 20
MIN_CHAIN_PEERS = 5


def subtree_bucket(n: int) -> str:
    return "0" if n == 0 else "1" if n == 1 else "2-3" if n <= 3 else "4-7" if n <= 7 else "8+"


def tier_is_known(cfg: GeneratorConfig, task_id) -> bool:
    """Whether the analysis gets to see this task's tier (the masking is deterministic per seed and task)."""
    return random.Random(f"{cfg.seed}:tiermask:{task_id}").random() >= cfg.missing_tier_rate


def _stats(values: list[float]) -> dict:
    v = sorted(values)
    pick = lambda q: round(v[int(q * (len(v) - 1))], 1)
    return {"n_peers": len(v), "p50_days": pick(0.5), "p90_days": pick(0.9), "p99_days": pick(0.99)}


def _percentile(values: list[float], mine: float):
    srt = sorted(values)
    return round(bisect_right(srt, mine) / len(srt), 3) if srt else None


class PeerIndex:
    def __init__(self, cfg: GeneratorConfig, world: World, history: History):
        self.cfg, self.world, self.history = cfg, world, history
        self.created = {t.id: t.created_at for t in world.tasks}
        self.tier_of = {t.id: (t.tier if t.tier and tier_is_known(cfg, t.id) else UNKNOWN_TIER)
                        for t in world.tasks}
        self.pace_of = {t.id: world.projects[t.project].archetype for t in world.tasks}
        self.done_at = self._done_times()
        self.sick = {tid for d in world.diseases for tid in d.task_ids}
        self.dependents = self._final_dependents()
        self.subtree = self._subtree_sizes()
        self._blocker_groups = self._build_blocker_groups()
        self._chain_pools = self._build_chain_pools()

    # ---- per-task facts ----
    def _done_times(self) -> dict:
        last_done = {}
        for r in self.history.state_rows:
            if r.event_type == "STATUS_CHANGED" and r.to_status == "DONE":
                last_done[r.task_id] = r.created_at
        return {t: at for t, at in last_done.items() if self.history.final_status[t] == "DONE"}

    def open_days(self, task_id) -> float:
        end = self.done_at.get(task_id, self.cfg.end)
        return (end - self.created[task_id]).total_seconds() / 86400

    def _final_dependents(self) -> dict:
        edges = set()
        for r in self.history.dependency_rows:
            if r.action == "ADDED":
                edges.add((r.task_id, r.depends_on_task_id))
            elif r.action == "REMOVED":
                edges.discard((r.task_id, r.depends_on_task_id))
        dependents = defaultdict(set)
        for task, dep in edges:
            dependents[dep].add(task)
        return dependents

    def _subtree_sizes(self) -> dict:
        """Transitive dependents at the end of the history. Dependents are always created later, so walking
        the tasks newest-first sees every dependent's subtree before its blocker's."""
        below: dict = {}
        for task in reversed(self.world.tasks):
            s = set()
            for d in self.dependents.get(task.id, ()):
                s.add(d)
                s |= below[d]
            below[task.id] = s
        return {t: len(s) for t, s in below.items()}

    # ---- chronic blocker: same tier, similar subtree ----
    def _build_blocker_groups(self) -> dict:
        groups = defaultdict(list)
        for t in self.world.tasks:
            if t.id in self.sick or not self.dependents.get(t.id):
                continue  # only healthy tasks that block someone are peers of a blocker
            days, bucket, pace, tier = (self.open_days(t.id), subtree_bucket(self.subtree[t.id]),
                                        self.pace_of[t.id], self.tier_of[t.id])
            for tier_key in {tier, ANY}:  # a known tier feeds its own groups and the tier-less ones
                groups[(tier_key, bucket, pace)].append(days)
                groups[(tier_key, bucket)].append(days)
                groups[(tier_key,)].append(days)
        return groups

    def blocker_peers(self, task_id) -> dict:
        tier, bucket, pace = self.tier_of[task_id], subtree_bucket(self.subtree[task_id]), self.pace_of[task_id]
        known = tier != UNKNOWN_TIER
        t = tier if known else ANY  # without a tier the comparison pools every tier
        levels = [(f"{'tier+' if known else ''}subtree+team_pace", (t, bucket, pace)),
                  (f"{'tier+' if known else ''}subtree", (t, bucket))]
        if known:
            levels.append(("tier", (t,)))
        levels.append(("all", (ANY,)))
        mine = self.open_days(task_id)
        for level, key in levels:
            values = self._blocker_groups.get(key, [])
            if len(values) >= MIN_BLOCKER_PEERS or level == "all":
                return {"level": level, "tier_used": tier if known and "tier" in level else None,
                        "subtree_bucket": bucket if "subtree" in level else None,
                        "team_pace": pace if "team_pace" in level else None,
                        "subtree_size": self.subtree[task_id], "open_days": round(mine, 1),
                        "percentile_among_peers": _percentile(values, mine),
                        **(_stats(values) if values else {"n_peers": 0})}
        raise AssertionError("unreachable: the 'all' level always returns")

    # ---- slow chain: same length, shape and tier ----
    def chain_end_to_end(self, task_ids: list) -> tuple[float, bool]:
        """Days from the first link's creation to the last link's DONE (or to the end if still open)."""
        last = task_ids[-1]
        done = last in self.done_at
        end = self.done_at[last] if done else self.cfg.end
        return (end - self.created[task_ids[0]]).total_seconds() / 86400, done

    def _build_chain_pools(self) -> dict:
        pools = defaultdict(list)
        for c in self.world.controls:
            days, done = self.chain_end_to_end(c.task_ids)
            if not done:
                continue  # only finished chains say how long a healthy one takes
            pace, tier = self.pace_of[c.task_ids[0]], self.tier_of[c.task_ids[0]]
            for tier_key in {tier, ANY}:
                pools[(c.length, tier_key, pace)].append(days)
                pools[(c.length, tier_key)].append(days)
        return pools

    def chain_peers(self, task_ids: list) -> dict:
        length, pace, tier = len(task_ids), self.pace_of[task_ids[0]], self.tier_of[task_ids[0]]
        known = tier != UNKNOWN_TIER
        t = tier if known else ANY
        days, done = self.chain_end_to_end(task_ids)
        levels = [(f"length+{'tier+' if known else ''}team_pace", (length, t, pace)),
                  (f"length{'+tier' if known else ''}", (length, t))]
        if known:
            levels.append(("length", (length, ANY)))
        for i, (level, key) in enumerate(levels):
            values = self._chain_pools.get(key, [])
            if len(values) >= MIN_CHAIN_PEERS or i == len(levels) - 1:
                return {"level": level, "length": length, "completed": done, "end_to_end_days": round(days, 1),
                        "tier_used": tier if known and "tier" in level else None,
                        "team_pace": pace if "team_pace" in level else None,
                        "percentile_among_peers": _percentile(values, days),
                        **(_stats(values) if values else {"n_peers": 0})}
        raise AssertionError("unreachable: the last level always returns")
