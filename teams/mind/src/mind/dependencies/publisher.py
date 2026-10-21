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
record is per-process, in-memory, bounded (oldest evicted once
`_MAX_PUBLISHED` is hit, preferring a fully-resolved task over one still
pending) and time-limited (`_MAX_PUBLISHED_AGE`) -- it relies on Chain
no-op'ing a re-proposed edge whenever the record doesn't survive that
long, not just across a restart. Time for both the TTL and the eviction/
expiry throttle is monotonic (`time.monotonic`), not wall-clock, so an NTP
correction or manual clock change can't silently disable either. A
cancelled call waits once more, shielded, for its send's real outcome
before honoring the cancellation, so a send that completes or fails during
that one extra wait is still recorded or logged -- but a second
cancellation during that wait is let through immediately unrecorded, so
cancellation (e.g. during shutdown) can never hang indefinitely.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import OrderedDict
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import timedelta
from uuid import UUID, uuid5

from mind.dependencies.event import build_dependency_event
from mind.dependencies.kafka_sink import DependencySink
from mind.embeddings.base import VectorStore
from mind.embeddings.dependency_resolution import resolve_dependency_targets

logger = logging.getLogger(__name__)

#: Never change: every id would change, and every retry already sent would
#: be seen as new, defeating Chain's dedup.
_NAMESPACE = UUID("914f772d-7854-498c-a738-c5e7f9a23572")

_MAX_PUBLISHED = 1000
_MAX_PUBLISHED_AGE = timedelta(hours=24)
_EXPIRE_SWEEP_INTERVAL = timedelta(minutes=1)


class DependencyPublisher:
    """One lock per task_id, held only around that task's own
    check-reserve-send section: unrelated tasks' Kafka round trips never
    wait on each other, while two calls for the same task still serialize.
    Each lock is removed once nothing is using it, so this never grows
    unboundedly."""

    def __init__(
        self,
        store: VectorStore,
        sink: DependencySink,
        *,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._store = store
        self._sink = sink
        self._clock = clock
        # task_id -> ({mention: target already sent}, last time a new edge was
        # recorded (monotonic seconds), whether the task was fully resolved then).
        # An OrderedDict so iteration order tracks recency (oldest first) --
        # move_to_end() on every write keeps the globally oldest entry at
        # next(iter(...)), an O(1) lookup, for eviction.
        self._published: OrderedDict[str, tuple[dict[str, UUID], float, bool]] = OrderedDict()
        self._last_expired_at: float | None = None
        self._locks: dict[str, asyncio.Lock] = {}
        self._lock_refs: dict[str, int] = {}
        self._locks_guard = asyncio.Lock()

    def _already_sent(self, task_id: str) -> dict[str, UUID]:
        entry = self._published.get(task_id)
        return entry[0] if entry else {}

    def _expire_stale_published(self) -> None:
        now = self._clock()
        sweep_interval = _EXPIRE_SWEEP_INTERVAL.total_seconds()
        if self._last_expired_at is not None and now - self._last_expired_at < sweep_interval:
            return
        self._last_expired_at = now
        stale = [
            task_id
            for task_id, (_, last_sent_at, _) in self._published.items()
            if now - last_sent_at > _MAX_PUBLISHED_AGE.total_seconds()
        ]
        for task_id in stale:
            del self._published[task_id]

    def _record_sent(
        self,
        task_id: str,
        task_id_normalized: str,
        already_sent: dict[str, UUID],
        newly_resolved: dict[str, UUID],
        all_resolved: bool,
    ) -> None:
        if task_id_normalized not in self._published and len(self._published) >= _MAX_PUBLISHED:
            # Oldest-first scan for the first fully-resolved entry to sacrifice;
            # falls back to the globally oldest (first) entry if none is resolved.
            oldest_task_id = next(
                (tid for tid, (_, _, resolved) in self._published.items() if resolved),
                next(iter(self._published)),
            )
            logger.warning(
                "dependencies: _published full (%d), evicting task %s to record task %s",
                _MAX_PUBLISHED,
                oldest_task_id,
                task_id,
            )
            del self._published[oldest_task_id]
        self._published[task_id_normalized] = (
            {**already_sent, **newly_resolved},
            self._clock(),
            all_resolved,
        )
        self._published.move_to_end(task_id_normalized)

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
        self._expire_stale_published()

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

        already_sent = self._already_sent(task_id_normalized)
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
            already_sent = self._already_sent(task_id_normalized)
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

            def record_sent() -> None:
                self._record_sent(
                    task_id, task_id_normalized, already_sent, newly_resolved, all_resolved
                )

            try:
                await asyncio.shield(send_task)
            except asyncio.CancelledError:
                if send_task.cancelled():
                    raise
                # shield kept send_task running despite the outer cancellation;
                # the thread may still reach Kafka, so wait once more, shielded,
                # for its real outcome before honoring the original cancellation.
                # A second cancellation here gives up waiting and propagates
                # immediately instead of re-looping, so a cancel (e.g. during
                # shutdown) can never hang -- at the accepted cost that a send
                # which only succeeds after that point goes unrecorded.
                try:
                    await asyncio.shield(send_task)
                except asyncio.CancelledError:
                    if send_task.cancelled():
                        raise
                    raise
                except Exception:
                    logger.exception("dependencies: task %s's cancelled send failed", task_id)
                else:
                    record_sent()
                raise
            else:
                record_sent()
                return all_resolved
