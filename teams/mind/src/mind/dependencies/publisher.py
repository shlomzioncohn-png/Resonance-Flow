"""#355: resolve a task's dependency mentions into targets and publish
task.dependencies.proposed (#133) to Chain.

Hand-off: `publish_dependencies` takes `task_id`, `tenant_id` and
`dependency_mentions` explicitly -- not read off #353's `MatchedReply`
itself; `adapter.py` does that (see README.md).

One shot, one event per resolved mention -- no retry, no local bookkeeping
of what's already been sent. `event_id = uuid5(task_id:target)` (#353's
pattern: a stable id derived from the edge itself, not from whatever else
happened to resolve alongside it in a given call) means a redelivered
task.created re-publishes the exact same event for an edge already sent,
which Chain dedups on event_id -- so there is nothing here to track. A
mention that doesn't resolve is logged and not retried: #355's scope ends
at "publish what's known when task.created arrives"; see README.md for why.
"""

from __future__ import annotations

import asyncio
import logging
from uuid import UUID, uuid5

from mind.dependencies.event import build_dependency_event
from mind.dependencies.kafka_sink import DependencySink
from mind.embeddings.base import VectorStore
from mind.embeddings.dependency_resolution import resolve_dependency_targets

logger = logging.getLogger(__name__)

#: Never change: every edge's id would change, and Chain would see a
#: redelivered send as new, defeating its dedup.
_NAMESPACE = UUID("914f772d-7854-498c-a738-c5e7f9a23572")


class DependencyPublisher:
    def __init__(self, store: VectorStore, sink: DependencySink) -> None:
        self._store = store
        self._sink = sink

    async def publish_dependencies(
        self,
        *,
        task_id: str,
        tenant_id: UUID,
        dependency_mentions: list[str],
    ) -> None:
        """Resolve `dependency_mentions` (#245) once and publish an edge for
        each one that resolves right now. Per mention, only the best
        candidate becomes an edge: the embeddings README notes that a close
        paraphrase pair (e.g. "login bug" vs "logout bug") can both clear
        the threshold, so taking every candidate above it would treat a
        near-miss as a real dependency."""

        mentions = [m for m in dependency_mentions if m.strip()]
        blank_count = len(dependency_mentions) - len(mentions)
        if blank_count:
            logger.warning(
                "dependencies: task %s dropped %d blank mention(s)",
                task_id,
                blank_count,
            )
        if not mentions:
            return

        task_uuid = UUID(task_id)
        task_id_normalized = str(task_uuid)

        resolved_lists = await resolve_dependency_targets(
            self._store, mentions, k=1, exclude_task_id=task_id_normalized
        )
        # Two mentions can resolve to the same target: dedup by target first,
        # keeping the higher confidence, so they don't race two sends for one
        # event_id -- Chain keeps whichever arrives first, confidence included.
        candidates: dict[UUID, float] = {}
        for mention, results in zip(mentions, resolved_lists):
            if not results:
                logger.warning(
                    "dependencies: task %s's mention %r did not resolve", task_id, mention
                )
                continue
            result = results[0]
            try:
                target_uuid = UUID(result.task_id)
            except ValueError:
                logger.warning(
                    "dependencies: task %s resolved target %r is not a UUID, skipping",
                    task_id,
                    result.task_id,
                )
                continue
            if target_uuid not in candidates or result.similarity > candidates[target_uuid]:
                candidates[target_uuid] = result.similarity

        for target_uuid, confidence in candidates.items():
            event_id = uuid5(_NAMESPACE, f"{task_id_normalized}:{target_uuid}")
            event = build_dependency_event(
                task_uuid, tenant_id, {target_uuid: confidence}, event_id=event_id
            )
            # Off the loop: send() blocks until Kafka acks.
            await asyncio.to_thread(self._sink.send, event)
