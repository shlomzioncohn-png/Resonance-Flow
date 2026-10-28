"""Whether a planted disease can already be told apart from healthy work in the data.

A pattern that starts shortly before the end of the history has not had time to look abnormal yet, however
strongly it was planted. The ground truth says so (`visible_in_data=false`), so the evaluation can leave such
cases out or count them apart instead of scoring a detector for missing what is not in the data.

When a pattern becomes visible:
- chronic blocking, bottleneck, stuck subgraph: when its root has been open longer than 90% of its healthy peers
  (the peer group's p90).
- slow chain: when the chain has taken longer end to end than 90% of its healthy peer chains.
- recurring blocker: when a task has been blocked a third time (healthy tasks are blocked at most 3 times, so
  this is the first moment more than a coin flip says "recurring"; see docs/severity.md).
"""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timedelta

from .model import DiseaseInstance, World
from .peers import PeerIndex
from .simulation import History

BLOCKS_TO_BE_VISIBLE = 3


class Visibility:
    def __init__(self, world: World, history: History, peers: PeerIndex):
        self.peers = peers
        self.created = {t.id: t.created_at for t in world.tasks}
        self.blocks: dict = defaultdict(list)
        for r in history.state_rows:
            if r.event_type == "BLOCKED":
                self.blocks[r.task_id].append(r.created_at)

    def visible_from(self, d: DiseaseInstance, observed: dict) -> datetime | None:
        """The moment the pattern first became visible in the data, or None if it never did (within the history)."""
        reference = observed["window_end"]
        if d.type == "recurring_blocker":
            third = [b[BLOCKS_TO_BE_VISIBLE - 1] for t in d.task_ids if len(b := self.blocks[t]) >= BLOCKS_TO_BE_VISIBLE]
            return min(third) if third else None
        if d.type == "slow_chain":
            p90 = observed["peer_chains"].get("p90_days")
            start = self.created[d.task_ids[0]]
        else:
            p90 = observed["peer_group"].get("p90_days")
            start = self.created[d.root_task_id]
        if p90 is None:
            return None
        moment = start + timedelta(days=p90)
        return moment if moment <= reference else None

    def annotate(self, d: DiseaseInstance, observed: dict) -> dict:
        moment = self.visible_from(d, observed)
        reason = None
        if moment is None:  # still going on at the end: too recent; already over: it never left the normal range
            reason = "too_recent" if observed["ongoing_at_end"] else "stayed_within_normal"
        return {"visible_in_data": moment is not None, "visible_from": moment, "not_visible_because": reason}
