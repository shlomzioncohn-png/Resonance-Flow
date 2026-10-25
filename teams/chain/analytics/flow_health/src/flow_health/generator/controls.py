"""Healthy control chains: the peers a slow chain is compared against.

Same lengths, tiers and linear shape as the slow-chain disease, but at normal speed, and spread over the
team paces (fast / medium / slow) so a slow chain can be compared with chains from a team of its own pace.
They are part of the healthy world (never labelled sick) and are listed in `healthy_controls.csv`.
"""
from __future__ import annotations

import random
from datetime import timedelta

from .config import GeneratorConfig
from .model import ControlChain, EdgeBatch, Project, TaskSpec, pick_upstream, task_uuid
from .timeutil import snap_to_work_time

CONTROL_LENGTHS = (4, 7, 12)  # the slow-chain lengths
CONTROL_TIERS = ("operational", "tactical")  # the slow-chain tiers
T0_RANGE = (0.03, 0.4)  # start early enough for most chains to finish within the history


def plant_control_chains(cfg: GeneratorConfig, projects: list[Project], normal: list[TaskSpec],
                         tasks: list[TaskSpec], edges: list[EdgeBatch]) -> list[ControlChain]:
    out = []
    for length in CONTROL_LENGTHS:
        for tier in CONTROL_TIERS:
            for pace in sorted({p.archetype for p in projects}):
                teams = [p.id for p in projects if p.archetype == pace]
                for k in range(cfg.control_chains_per_group):
                    chain_id = f"control-chain-{length}-{tier}-{pace}-{k + 1}"
                    out.append(_plant_chain(cfg, chain_id, length, tier, teams, normal, tasks, edges))
    return out


def _plant_chain(cfg: GeneratorConfig, chain_id: str, length: int, tier: str, teams: list[int],
                 normal: list[TaskSpec], tasks: list[TaskSpec], edges: list[EdgeBatch]) -> ControlChain:
    rng = random.Random(f"{cfg.seed}:{chain_id}")
    project = rng.choice(teams)
    cursor = snap_to_work_time(cfg.start + timedelta(seconds=rng.uniform(*T0_RANGE) * cfg.history_days * 86400), rng)
    links: list[TaskSpec] = []
    for i in range(length):
        if i:
            nxt = snap_to_work_time(cursor + timedelta(hours=rng.uniform(0.5, 3)), rng)
            cursor = max(nxt, cursor + timedelta(seconds=60))  # strictly increasing: rank = chain order
        link = TaskSpec(task_uuid(cfg.seed, f"{chain_id}:{i}"), project, cursor, tier=tier,
                        kind="control_chain", control_id=chain_id)
        links.append(link)
        tasks.append(link)
        at = cursor + timedelta(seconds=rng.uniform(1, 30))
        if i:
            edges.append(EdgeBatch(at, "ADDED", link.id, (links[i - 1].id,), "MIND"))
        elif rng.random() < 0.6:  # like a planted root, the head hangs off ordinary work
            upstream = pick_upstream(rng, normal, cursor, project)
            if upstream:
                edges.append(EdgeBatch(at, "ADDED", link.id, (upstream.id,), "MIND"))
    return ControlChain(chain_id, length, "linear", tier, project, [t.id for t in links])
