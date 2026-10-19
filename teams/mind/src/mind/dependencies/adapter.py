"""#355: the on_matched adapter plugging DependencyPublisher into
TaskCreationFlow, and the retry tracking for a dependency that doesn't
resolve yet.

A mention whose target has no task_id yet makes publish_dependencies
return False (something's still outstanding). DependencyAdapter remembers
that task's (tenant_id, dependency_mentions) and retries it -- not just
whatever task just matched -- on every subsequent on_task_created call,
since any newly created task might be exactly what was missing. Dropped
once publish_dependencies reports nothing outstanding.
"""

from __future__ import annotations

from uuid import UUID

from mind.dependencies.publisher import DependencyPublisher
from mind.task_creation.reply import MatchedReply


class DependencyAdapter:
    """Pass `.on_task_created` as `TaskCreationFlow`'s `on_matched`."""

    def __init__(self, publisher: DependencyPublisher) -> None:
        self._publisher = publisher
        self._pending: dict[str, tuple[UUID, tuple[str, ...]]] = {}

    async def on_task_created(self, matched: MatchedReply) -> None:
        await self._publish(
            matched.task_id, matched.tenant_id, matched.request.dependency_mentions
        )
        for task_id, (tenant_id, mentions) in list(self._pending.items()):
            if task_id == matched.task_id:
                continue
            await self._publish(task_id, tenant_id, mentions)

    async def _publish(
        self, task_id: str, tenant_id: UUID, mentions: tuple[str, ...]
    ) -> None:
        if not mentions:
            return
        fully_resolved = await self._publisher.publish_dependencies(
            task_id=task_id, tenant_id=tenant_id, dependency_mentions=list(mentions)
        )
        if fully_resolved:
            self._pending.pop(task_id, None)
        else:
            self._pending[task_id] = (tenant_id, mentions)
