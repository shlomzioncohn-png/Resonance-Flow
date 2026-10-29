"""#355: resolve a task's dependency mentions into targets and publish
task.dependencies.proposed (#133) to Chain.

Hand-off: `publish_dependencies` takes `task_id`, `tenant_id` and
`dependency_mentions` explicitly -- not read off #353's `MatchedReply`
itself; `adapter.py` does that (see README.md).

One event per resolved mention. `event_id = uuid5(task_id:target)` (#353's
pattern: a stable id derived from the edge itself, not from whatever else
happened to resolve alongside it in a given call) means a redelivered
task.created re-publishes the exact same event for an edge already sent,
which Chain dedups on event_id -- so republishing one is harmless.

A mention that doesn't resolve is logged and, when `pending` is given
(#918), registered for retry: `retry.py`'s `retry_pending_dependencies`
calls this same method again later with exactly the mentions still
pending for a tenant, so a mention that resolves on retry goes through
the identical logic below as one that resolved on the first try, and a
mention that now resolves is discarded from `pending` whether this is
its first attempt or its tenth. Without `pending` (the default, and
every call site before #918's runner wiring), this is #355's original
one-shot behavior -- see README.md.
"""

from __future__ import annotations

import asyncio
import logging
from uuid import UUID, uuid5

from mind.dependencies.event import build_dependency_event
from mind.dependencies.kafka_sink import DependencySink
from mind.dependencies.pending import PendingDependencyStore
from mind.embeddings.base import SearchResult, VectorIndex
from mind.embeddings.dependency_resolution import resolve_dependency_targets

logger = logging.getLogger(__name__)

#: Never change: every edge's id would change, and Chain would see a
#: redelivered send as new, defeating its dedup.
_NAMESPACE = UUID("914f772d-7854-498c-a738-c5e7f9a23572")


def _resolved_target(task_id: UUID, mention: str, results: list[SearchResult]) -> UUID | None:
    """The mention's target, or None if it isn't resolved yet -- any of no
    candidate, a malformed target, or (defensively; `exclude_task_id`
    should already have filtered this) resolving to the task itself."""

    if not results:
        logger.warning("dependencies: task %s's mention %r did not resolve", task_id, mention)
        return None
    try:
        target_uuid = UUID(results[0].task_id)
    except ValueError:
        logger.warning(
            "dependencies: task %s resolved target %r is not a UUID, skipping",
            task_id,
            results[0].task_id,
        )
        return None
    # The store's own exclude_task_id filter is a case-sensitive SQL string
    # compare -- a target stored in different case than our normalized
    # lowercase id would slip through it. Compare as UUIDs.
    if target_uuid == task_id:
        logger.warning(
            "dependencies: task %s resolved itself as a dependency target, skipping", task_id
        )
        return None
    return target_uuid


class DependencyPublisher:
    def __init__(
        self,
        index: VectorIndex,
        sink: DependencySink,
        pending: PendingDependencyStore | None = None,
    ) -> None:
        self._index = index
        self._sink = sink
        self._pending = pending

    async def publish_dependencies(
        self,
        *,
        task_id: UUID,
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

        task_id_normalized = str(task_id)
        store = self._index.for_tenant(tenant_id)

        resolved_lists = await resolve_dependency_targets(
            store, mentions, k=1, exclude_task_id=task_id_normalized
        )
        # Two mentions can resolve to the same target: dedup by target first,
        # keeping the higher similarity, so they don't race two sends for one
        # event_id -- Chain keeps whichever arrives first, confidence included.
        # `mentions_for` tracks every mention behind each target, so #918's
        # pending store is only cleared for a mention once *its* edge has
        # actually been sent -- see the loop below.
        candidates: dict[UUID, float] = {}
        mentions_for: dict[UUID, list[str]] = {}
        for mention, results in zip(mentions, resolved_lists):
            target_uuid = _resolved_target(task_id, mention, results)
            if target_uuid is None:
                if self._pending is not None:
                    await self._pending.add(task_id, tenant_id, mention)
                continue
            # #476 review: Chain's README takes this field at face value as
            # a 0..1 confidence (defaulting to 1.0 for edges not sourced from
            # Mind) and sums it directly into blockingConfidenceSum/
            # dependentConfidenceSum -- it isn't calibrated against raw
            # similarity on their side. Switching what we send here would
            # silently change their priority_score with no coordination, so
            # this still sends raw similarity, not #476's new confidence.
            similarity = results[0].similarity
            if target_uuid not in candidates or similarity > candidates[target_uuid]:
                candidates[target_uuid] = similarity
            mentions_for.setdefault(target_uuid, []).append(mention)

        for target_uuid, confidence in candidates.items():
            event_id = uuid5(_NAMESPACE, f"{task_id_normalized}:{target_uuid}")
            event = build_dependency_event(
                task_id, tenant_id, {target_uuid: confidence}, event_id=event_id
            )
            # Off the loop: send() blocks until Kafka acks. Raises on failure --
            # the mentions behind this target are only cleared from `pending`
            # below, once the send actually succeeded (#918 review: clearing
            # them beforehand lost an edge for good on a failed send, since
            # nothing would ever retry a mention no longer marked pending).
            await asyncio.to_thread(self._sink.send, event)
            if self._pending is not None:
                for mention in mentions_for[target_uuid]:
                    await self._pending.discard(task_id, mention)
            # Ids only, never the mention: it is written from the content (#359,
            # so a demo shows the dependency stage the way it shows the others).
            logger.info(
                "dependencies: task %s depends on task %s (confidence %.3f), published",
                task_id,
                target_uuid,
                confidence,
            )
