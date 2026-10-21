"""#355: the on_matched adapter plugging DependencyPublisher into
TaskCreationFlow, and the retry tracking for a dependency that doesn't
resolve yet.

A mention whose target has no task_id yet makes publish_dependencies
return False (something's still outstanding). DependencyAdapter remembers
that task's (tenant_id, dependency_mentions) and retries it -- not just
whatever task just matched -- on every subsequent on_task_created call,
since any newly created task might be exactly what was missing. That sweep
runs in the background (fire-and-forget off on_task_created's own return)
so one reply's latency doesn't include resolving up to _MAX_PENDING other
tasks. Dropped once publish_dependencies reports nothing outstanding,
capped at _MAX_PENDING entries, and expired after _MAX_PENDING_AGE so a
mention that never resolves doesn't grow this dict (and the re-resolution
work on every sweep) without bound. A target created directly in Inbox
(never matching a Mind-originated reply) is only picked up if some later,
unrelated Mind-originated reply happens to trigger a sweep -- retry_pending
is not wired to the overdue timer (an earlier attempt at that crossed
threads unsafely; see task_creation/README.md's git history), so there is
currently no periodic retry independent of Mind's own traffic. A proper fix
needs its own design (tracked separately), not a patch here.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from datetime import timedelta
from uuid import UUID

from mind.dependencies.publisher import DependencyPublisher
from mind.task_creation.reply import MatchedReply

logger = logging.getLogger(__name__)

_MAX_PENDING = 1000
_MAX_PENDING_AGE = timedelta(hours=24)


class DependencyAdapter:
    """Pass `.on_task_created` as `TaskCreationFlow`'s `on_matched`.
    `retry_pending` runs from there, on each reply that leaves something
    pending -- not wired to the overdue timer's `on_tick`, since that would
    call it from a different thread/loop than the one `DependencyPublisher`'s
    locks are created on. See task_creation/README.md, "Running it"."""

    def __init__(
        self,
        publisher: DependencyPublisher,
        *,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._publisher = publisher
        self._clock = clock
        self._pending: dict[str, tuple[UUID, tuple[str, ...], float]] = {}
        self._background_tasks: set[asyncio.Task[None]] = set()
        self._sweep_lock = asyncio.Lock()
        self._rerun_requested = False

    async def on_task_created(self, matched: MatchedReply) -> None:
        try:
            task_id = str(UUID(matched.task_id))
        except ValueError:
            logger.warning("dependencies: task_id %r is not a UUID, skipping", matched.task_id)
            return
        await self._publish(task_id, matched.tenant_id, matched.request.dependency_mentions)
        if self._pending:
            self._retry_pending_in_background(skip=task_id)

    def _retry_pending_in_background(self, *, skip: str | None) -> None:
        task = asyncio.ensure_future(self.retry_pending(skip=skip))
        self._background_tasks.add(task)
        task.add_done_callback(self._background_tasks.discard)
        task.add_done_callback(self._log_if_failed)

    @staticmethod
    def _log_if_failed(task: asyncio.Task[None]) -> None:
        if not task.cancelled() and (exc := task.exception()) is not None:
            logger.error("dependencies: background retry_pending sweep failed", exc_info=exc)

    async def retry_pending(self, *, skip: str | None = None) -> None:
        if self._sweep_lock.locked():
            self._rerun_requested = True
            return
        async with self._sweep_lock:
            first_pass = True
            while True:
                self._rerun_requested = False
                self._expire_stale()
                # skip only applies to the pass triggered by this call's own
                # reply; a rerun pass has no such reply to exclude.
                pass_skip = skip if first_pass else None
                for task_id, (tenant_id, mentions, _added_at) in list(self._pending.items()):
                    if task_id == pass_skip:
                        continue
                    try:
                        await self._publish(task_id, tenant_id, mentions)
                    except Exception:
                        logger.exception("dependencies: retrying pending task %s failed", task_id)
                first_pass = False
                if not self._rerun_requested:
                    break

    def _expire_stale(self) -> None:
        now = self._clock()
        stale = [
            task_id
            for task_id, (_, _, added_at) in self._pending.items()
            if now - added_at > _MAX_PENDING_AGE.total_seconds()
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
        existing = self._pending.get(task_id)
        if existing is None and len(self._pending) >= _MAX_PENDING:
            logger.warning(
                "dependencies: dropping task %s's unresolved mentions, _pending is full (%d)",
                task_id,
                _MAX_PENDING,
            )
            return
        added_at = existing[2] if existing is not None else self._clock()
        self._pending[task_id] = (tenant_id, mentions, added_at)
