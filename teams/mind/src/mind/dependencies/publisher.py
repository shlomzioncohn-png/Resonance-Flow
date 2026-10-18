"""#355: resolve a task's dependency mentions into targets and publish
task.dependencies.proposed (#133) to Chain.

Hand-off: `publish_dependencies` takes `task_id`, `tenant_id` and
`dependency_mentions` explicitly -- not read off #353's `MatchedReply`,
which doesn't carry them yet (see README.md).

Per mention, only the best resolved candidate becomes an edge: the
embeddings README notes that a close paraphrase pair (e.g. "login bug" vs
"logout bug") can both clear the threshold, so taking every candidate above
it would treat a near-miss as a real dependency. An unresolved mention is
neither dropped nor force-published -- a later call with the same mentions
picks it up once it resolves. The sent-edges record is per-process,
in-memory, and relies on Chain no-op'ing a re-proposed edge after a restart.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from uuid import UUID

from mind.dependencies.event import build_dependency_event
from mind.dependencies.kafka_sink import DependencySink
from mind.embeddings.base import VectorStore
from mind.embeddings.dependency_resolution import resolve_dependency_targets

logger = logging.getLogger(__name__)


class DependencyPublisher:
    """One lock per task_id, held only around that task's own
    check-reserve-send section: unrelated tasks' Kafka round trips never
    wait on each other, while two calls for the same task still serialize.
    Each lock is removed once nothing is using it, so this never grows
    unboundedly."""

    def __init__(self, store: VectorStore, sink: DependencySink) -> None:
        self._store = store
        self._sink = sink
        self._published: dict[str, set[UUID]] = {}
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
    ) -> None:
        """Resolve `dependency_mentions` (#245) and publish whatever is new.

        A task with no mentions at all publishes nothing.
        """
        if not dependency_mentions:
            return

        task_uuid = UUID(task_id)

        resolved_lists = await resolve_dependency_targets(
            self._store, dependency_mentions, exclude_task_id=task_id
        )
        candidates: dict[UUID, float] = {}
        for results in resolved_lists:
            for result in results:
                try:
                    target_uuid = UUID(result.task_id)
                except ValueError:
                    logger.warning(
                        "dependencies: task %s resolved target %r is not a UUID, skipping",
                        task_id,
                        result.task_id,
                    )
                    continue
                if target_uuid == task_uuid:
                    # A mention resolving to the task's own id is not a real
                    # dependency -- a task cannot depend on itself.
                    continue
                if target_uuid not in candidates or result.similarity > candidates[target_uuid]:
                    candidates[target_uuid] = result.similarity
                break

        async with self._task_lock(task_id):
            already_sent = self._published.get(task_id, set())
            new_edges = {
                target: confidence
                for target, confidence in candidates.items()
                if target not in already_sent
            }
            if not new_edges:
                logger.debug(
                    "dependencies: task %s has no new edges to publish "
                    "(%d already sent, %d mentions resolved)",
                    task_id,
                    len(already_sent),
                    len(dependency_mentions),
                )
                return

            event = build_dependency_event(task_uuid, tenant_id, new_edges)
            # Off the loop: send() blocks until Kafka acks.
            send_task = asyncio.ensure_future(asyncio.to_thread(self._sink.send, event))
            try:
                await asyncio.shield(send_task)
            except asyncio.CancelledError:
                if send_task.cancelled():
                    raise
                # This call was cancelled, but the thread running send() can't
                # be interrupted -- it may still reach Kafka. Wait for the
                # real outcome so already_sent only updates if it did, then
                # honor the cancellation.
                await send_task
                self._published[task_id] = already_sent | set(new_edges)
                raise
            else:
                self._published[task_id] = already_sent | set(new_edges)
