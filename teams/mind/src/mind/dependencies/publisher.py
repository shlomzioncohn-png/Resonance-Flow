"""#355: resolve a task's dependency mentions into targets and publish
task.dependencies.proposed (#133) to Chain.

`publish_dependencies` takes `task_id`, `tenant_id` and `dependency_mentions`
(free text, #245's input shape) explicitly. Where those three come from --
extraction's LLM call, and matching Inbox's task.created reply to a real
task_id (#353's `on_matched` hook, `MatchedReply`) -- is not this module's
concern: that hand-off is #349's pipeline to build, which does not exist yet,
the same position #353 itself is in (see its README, "Running it"). Whoever
wires it calls this with those three values, however it gets them.

A mention that resolves to nothing yet (its target has no task_id -- the
issue's step 4) is neither dropped nor force-published: calling
`publish_dependencies` again later with the same mentions picks it up once it
resolves, and the per-task published-edge tracking below makes a repeat call
safe within this process -- an edge already sent this process is never sent
again, matching #133's contract ("re-publishing adds edges, never replaces
them"). That tracking is in-memory only and does not survive a restart; after
one, the same mentions publish the same edges again, relying on Chain
treating a re-proposed edge (same `depends_on_task_id`, same `task_id`) as a
no-op rather than a hard duplicate. When, or whether, to call again is the
caller's decision, not built here.
"""

from __future__ import annotations

import asyncio
import logging
from uuid import UUID

from mind.dependencies.event import build_dependency_event
from mind.dependencies.kafka_sink import DependencySink
from mind.embeddings.base import VectorStore
from mind.embeddings.dependency_resolution import resolve_dependency_targets

logger = logging.getLogger(__name__)


class DependencyPublisher:
    """Stateful: tracks which depends_on_task_id have already been sent for
    each task_id, so a duplicate call (#353's `on_matched` can fire twice for
    the same reply) never re-publishes an edge."""

    def __init__(self, store: VectorStore, sink: DependencySink) -> None:
        self._store = store
        self._sink = sink
        self._published: dict[str, set[str]] = {}
        self._lock = asyncio.Lock()

    async def publish_dependencies(
        self,
        *,
        task_id: str,
        tenant_id: UUID,
        dependency_mentions: list[str],
    ) -> None:
        """Resolve `dependency_mentions` (#245) and publish whatever is new.

        A task with no mentions at all publishes nothing: `has_dependencies`
        false means no event, never called with anything to resolve.
        """
        if not dependency_mentions:
            return

        # Parsed up front, every call: a malformed task_id from the caller
        # should fail now, not after the (expensive) resolution below.
        task_uuid = UUID(task_id)

        resolved_lists = await resolve_dependency_targets(self._store, dependency_mentions)
        candidates: dict[str, float] = {}
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
                if result.task_id not in candidates or result.similarity > candidates[result.task_id]:
                    candidates[result.task_id] = result.similarity

        async with self._lock:
            already_sent = self._published.setdefault(task_id, set())
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
            # Off the loop: send() blocks until Kafka acks (see
            # task_creation/send.py for the same pattern).
            await asyncio.to_thread(self._sink.send, event)
            already_sent.update(new_edges)
