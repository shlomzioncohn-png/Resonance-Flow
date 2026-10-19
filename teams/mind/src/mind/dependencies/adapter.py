"""#355: the on_matched adapter plugging DependencyPublisher into
TaskCreationFlow, and the retry tracking for a dependency that doesn't
resolve yet.

A mention whose target has no task_id yet makes publish_dependencies
return False (something's still outstanding). DependencyAdapter remembers
that task's (tenant_id, dependency_mentions) and retries it -- not just
whatever task just matched -- on every subsequent on_task_created call,
since any newly created task might be exactly what was missing, AND on
every retry_pending() call from the overdue timer, since a target created
directly in Inbox (never matching a Mind-originated reply) would otherwise
never get picked up. Dropped once publish_dependencies reports nothing
outstanding, capped at _MAX_PENDING entries, and expired after
_MAX_PENDING_AGE so a mention that never resolves doesn't grow this dict
(and the re-resolution work on every call) without bound.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from uuid import UUID

from mind.dependencies.publisher import DependencyPublisher
from mind.task_creation.reply import MatchedReply

logger = logging.getLogger(__name__)

_MAX_PENDING = 1000
_MAX_PENDING_AGE = timedelta(hours=24)


class DependencyAdapter:
    """Pass `.on_task_created` as `TaskCreationFlow`'s `on_matched`, and
    `.retry_pending` as its `on_tick` (run by the overdue timer)."""

    def __init__(
        self,
        publisher: DependencyPublisher,
        *,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._publisher = publisher
        self._clock = clock
        self._pending: dict[str, tuple[UUID, tuple[str, ...], datetime]] = {}

    async def on_task_created(self, matched: MatchedReply) -> None:
        await self._publish(
            matched.task_id, matched.tenant_id, matched.request.dependency_mentions
        )
        await self.retry_pending(skip=str(UUID(matched.task_id)))

    async def retry_pending(self, *, skip: str | None = None) -> None:
        self._expire_stale()
        for task_id, (tenant_id, mentions, _added_at) in list(self._pending.items()):
            if task_id == skip:
                continue
            try:
                await self._publish(task_id, tenant_id, mentions)
            except Exception:
                logger.exception("dependencies: retrying pending task %s failed", task_id)

    def _expire_stale(self) -> None:
        now = self._clock()
        stale = [
            task_id
            for task_id, (_, _, added_at) in self._pending.items()
            if now - added_at > _MAX_PENDING_AGE
        ]
        for task_id in stale:
            logger.warning(
                "dependencies: task %s's dependency mentions expired unresolved after %s",
                task_id,
                _MAX_PENDING_AGE,
            )
            del self._pending[task_id]

    async def _publish(self, task_id: str, tenant_id: UUID, mentions: tuple[str, ...]) -> None:
        if not mentions:
            return
        task_id = str(UUID(task_id))
        fully_resolved = await self._publisher.publish_dependencies(
            task_id=task_id, tenant_id=tenant_id, dependency_mentions=list(mentions)
        )
        if fully_resolved:
            self._pending.pop(task_id, None)
            return
        if task_id not in self._pending and len(self._pending) >= _MAX_PENDING:
            logger.warning(
                "dependencies: dropping task %s's unresolved mentions, _pending is full (%d)",
                task_id,
                _MAX_PENDING,
            )
            return
        self._pending[task_id] = (tenant_id, mentions, self._clock())
