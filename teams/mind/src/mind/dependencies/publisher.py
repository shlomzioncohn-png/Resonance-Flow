"""#355: resolve a task's dependency mentions into targets and publish
task.dependencies.proposed (#133) to Chain.

Hand-off: `publish_dependencies` takes `task_id`, `tenant_id` and
`dependency_mentions` explicitly -- not read off #353's `MatchedReply`
itself; `adapter.py` does that (see README.md).

Per mention, only the best resolved candidate becomes an edge: the
embeddings README notes that a close paraphrase pair (e.g. "login bug" vs
"logout bug") can both clear the threshold, so taking every candidate above
it would treat a near-miss as a real dependency. An unresolved mention is
neither dropped nor force-published -- a later call with the same mentions
picks it up once it resolves, and `publish_dependencies` returns whether
everything resolved so the caller knows whether to retry. The sent-edges
record is per-process, in-memory, and relies on Chain no-op'ing a
re-proposed edge after a restart.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from uuid import UUID, uuid5

from mind.dependencies.event import build_dependency_event
from mind.dependencies.kafka_sink import DependencySink
from mind.embeddings.base import VectorStore
from mind.embeddings.dependency_resolution import resolve_dependency_targets

logger = logging.getLogger(__name__)

#: Never change: every id would change, and every retry already sent would
#: be seen as new, defeating Chain's dedup.
_NAMESPACE = UUID("914f772d-7854-498c-a738-c5e7f9a23572")


class DependencyPublisher:
    """One lock per task_id, held only around that task's own
    check-reserve-send section: unrelated tasks' Kafka round trips never
    wait on each other, while two calls for the same task still serialize.
    Each lock is removed once nothing is using it, so this never grows
    unboundedly."""

    def __init__(self, store: VectorStore, sink: DependencySink) -> None:
        self._store = store
        self._sink = sink
        self._published: dict[str, dict[str, UUID]] = {}  # task_id -> {mention: target already sent}
        self._locks: dict[str, asyncio.Lock] = {}
        self._lock_refs: dict[str, int] = {}
        self._locks_guard = asyncio.Lock()

    @asynccontextmanager
    async def _task_lock(self, task_id: str) -> AsyncIterator[None]:
        async with self._locks_guard:
            lock = self._locks.setdefault(task_id, asyncio.Lock())
            self._lock_refs[task_id] = self._lock_refs.get(task_id, 0) + 1
        try:
            async with lock:
                yield
        finally:
            async with self._locks_guard:
                self._lock_refs[task_id] -= 1
                if self._lock_refs[task_id] == 0:
                    del self._locks[task_id]
                    del self._lock_refs[task_id]

    async def publish_dependencies(
        self,
        *,
        task_id: str,
        tenant_id: UUID,
        dependency_mentions: list[str],
    ) -> bool:
        """Resolve `dependency_mentions` (#245) and publish whatever is new.

        Returns whether every mention resolved to some target -- False means
        at least one is still outstanding and the caller should consider
        retrying later. A task with no mentions at all returns True.
        """
        if not dependency_mentions:
            return True

        mentions = [m for m in dependency_mentions if m.strip()]
        blank_count = len(dependency_mentions) - len(mentions)
        if blank_count:
            logger.warning(
                "dependencies: task %s dropped %d blank mention(s)",
                task_id,
                blank_count,
            )
        if not mentions:
            return True

        task_uuid = UUID(task_id)
        task_id_normalized = str(task_uuid)

        already_sent = self._published.get(task_id_normalized, {})
        pending_mentions = [m for m in mentions if m not in already_sent]

        resolved_lists = (
            await resolve_dependency_targets(
                self._store, pending_mentions, k=1, exclude_task_id=task_id_normalized
            )
            if pending_mentions
            else []
        )
        candidates: dict[UUID, float] = {}
        newly_resolved: dict[str, UUID] = {}
        unresolved = False
        for mention, results in zip(pending_mentions, resolved_lists):
            if not results:
                unresolved = True
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
                unresolved = True
                continue
            newly_resolved[mention] = target_uuid
            if target_uuid not in candidates or result.similarity > candidates[target_uuid]:
                candidates[target_uuid] = result.similarity

        all_resolved = not unresolved

        async with self._task_lock(task_id_normalized):
            already_sent = self._published.get(task_id_normalized, {})
            sent_targets = set(already_sent.values())
            new_edges = {
                target: confidence
                for target, confidence in candidates.items()
                if target not in sent_targets
            }
            if not new_edges:
                logger.debug(
                    "dependencies: task %s has no new edges to publish "
                    "(%d already sent, %d mentions resolved)",
                    task_id,
                    len(sent_targets),
                    len(mentions),
                )
                return all_resolved

            event_id = uuid5(
                _NAMESPACE, f"{task_id_normalized}:{sorted(str(t) for t in new_edges)}"
            )
            event = build_dependency_event(task_uuid, tenant_id, new_edges, event_id=event_id)
            # Off the loop: send() blocks until Kafka acks.
            send_task = asyncio.ensure_future(asyncio.to_thread(self._sink.send, event))
            try:
                await asyncio.shield(send_task)
            except asyncio.CancelledError:
                if send_task.cancelled():
                    raise
                # This call was cancelled, but the thread running send() can't
                # be interrupted -- it may still reach Kafka. Wait for the real
                # outcome, shielded so a second cancellation here can't cut this
                # wait short, so already_sent only updates if it actually
                # succeeded -- then honor the original cancellation regardless.
                try:
                    await asyncio.shield(send_task)
                except Exception:
                    logger.exception(
                        "dependencies: task %s's cancelled send failed", task_id
                    )
                else:
                    self._published[task_id_normalized] = {**already_sent, **newly_resolved}
                raise
            else:
                self._published[task_id_normalized] = {**already_sent, **newly_resolved}
                return all_resolved
