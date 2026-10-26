"""How bad each planted disease actually is, as one score (0-100). Definition: docs/severity.md.

The planted `severity` label (subtle / moderate / severe) only says how strongly the pattern was planted;
this score says how much it matters: how unusual it is, how far it overran its estimate, how close the
deadlines it threatens are, and how much work it holds up.
"""
from __future__ import annotations

import math
from collections import Counter
from datetime import datetime

from .config import GeneratorConfig
from .model import DiseaseInstance, World, typical_minutes
from .peers import PeerIndex
from .simulation import History

WEIGHTS = {"abnormality": 0.35, "overrun": 0.30, "deadline": 0.25, "scale": 0.10}
HIGH_FROM, MEDIUM_FROM = 66.0, 33.0  # score thresholds of the level
HEALTHY_MAX_BLOCKS = 2  # a healthy task is blocked at most this often
FLAPPING_FULL = 10  # blocks at which the recurring-blocker abnormality is 1
OVERRUN_FULL = 100.0  # actual/expected ratio at which the overrun is 1
DEADLINE_WINDOW_DAYS = 30.0  # a deadline further away than this adds no pressure
MISSED_BASE = 0.6  # a deadline that was just missed; the lateness adds the rest, up to a month late
SCALE_FULL = 12  # tasks held up at which the scale is 1


def _clamp(x: float) -> float:
    return max(0.0, min(1.0, x))


def abnormality_from_percentile(percentile: float | None) -> float:
    """The median of the peers scores 0, the most extreme peer scores 1."""
    return 0.0 if percentile is None else _clamp((percentile - 0.5) / 0.5)


def abnormality_from_blocks(blocks: int) -> float:
    return _clamp((blocks - HEALTHY_MAX_BLOCKS) / (FLAPPING_FULL - HEALTHY_MAX_BLOCKS))


def overrun_score(ratio: float) -> float:
    """1x the estimate (or less) scores 0, 100x scores 1, in between on a log scale."""
    return 0.0 if ratio <= 1.0 else _clamp(math.log2(ratio) / math.log2(OVERRUN_FULL))


def deadline_urgency(slack_days: float) -> float:
    """A missed deadline scores 0.6, rising to 1 when a month late; one still ahead falls from 0.6 to 0 a month out."""
    if slack_days <= 0:
        return MISSED_BASE + (1 - MISSED_BASE) * _clamp(-slack_days / DEADLINE_WINDOW_DAYS)
    return MISSED_BASE * _clamp(1.0 - slack_days / DEADLINE_WINDOW_DAYS)


def level_of(score: float) -> str:
    return "high" if score >= HIGH_FROM else "medium" if score >= MEDIUM_FROM else "low"


class SeverityScorer:
    def __init__(self, cfg: GeneratorConfig, world: World, history: History, peers: PeerIndex):
        self.tasks = {t.id: t for t in world.tasks}
        self.projects = world.projects
        self.peers = peers
        self.first_active: dict = {}
        self.blocks: Counter = Counter()
        for r in history.state_rows:
            if r.event_type == "BLOCKED":
                self.blocks[r.task_id] += 1
            elif r.to_status == "ACTIVE" and r.task_id not in self.first_active:
                self.first_active[r.task_id] = r.created_at

    # ---- the four parts ----
    def _abnormality(self, d: DiseaseInstance, observed: dict) -> float:
        if d.type == "recurring_blocker":
            return abnormality_from_blocks(max(self.blocks[t] for t in d.task_ids))
        if d.type == "slow_chain":
            return abnormality_from_percentile(observed["peer_chains"]["percentile_among_peers"])
        return abnormality_from_percentile(observed["peer_group"]["percentile_among_peers"])

    def _overrun_ratio(self, d: DiseaseInstance, reference: datetime) -> float:
        """Time the pattern's tasks have been active against what they were estimated (or normally take)."""
        actual = expected = 0.0
        for t in (d.task_ids if d.type == "slow_chain" else [d.root_task_id]):
            started = self.first_active.get(t)
            if started is None or started > reference:
                continue
            done = self.peers.done_at.get(t)
            end = done if done is not None and done <= reference else reference
            task = self.tasks[t]
            actual += (end - started).total_seconds() / 60
            expected += task.effort_estimate_minutes or typical_minutes(self.projects[task.project], task.tier)
        return actual / expected if expected else 0.0

    def _deadline_pressure(self, d: DiseaseInstance, reference: datetime) -> tuple[float, float | None]:
        """The most urgent deadline among the tasks the pattern involves (it, and whatever it holds up)."""
        best, slack_of_best = 0.0, None
        for t in d.task_ids:
            deadline = self.tasks[t].deadline
            if deadline is None:
                continue
            done = self.peers.done_at.get(t)
            at = done if done is not None and done <= reference else reference  # slack when it finished, else now
            slack = (deadline - at).total_seconds() / 86400
            urgency = deadline_urgency(slack)
            if slack_of_best is None or urgency > best:
                best, slack_of_best = urgency, slack
        return best, slack_of_best

    def score(self, d: DiseaseInstance, observed: dict) -> dict:
        reference = observed["window_end"]
        ratio = self._overrun_ratio(d, reference)
        pressure, slack = self._deadline_pressure(d, reference)
        held_up = max(1, len(d.task_ids) - 1)
        parts = {
            "abnormality": self._abnormality(d, observed),
            "overrun": overrun_score(ratio),
            "deadline": pressure,
            "scale": _clamp(held_up / SCALE_FULL),
        }
        total = round(100 * sum(WEIGHTS[k] * v for k, v in parts.items()), 1)
        return {
            "severity_score": total,
            "severity_level": level_of(total),
            "severity_basis": {**{k: round(v, 3) for k, v in parts.items()},
                               "overrun_ratio": round(ratio, 2),
                               "tightest_deadline_slack_days": None if slack is None else round(slack, 1),
                               "tasks_held_up": held_up},
        }
