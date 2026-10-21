"""#355: the on_matched adapter plugging DependencyPublisher into
TaskCreationFlow.

Pass `.on_task_created` as `TaskCreationFlow`'s `on_matched`: publishes
whatever of a task's dependency mentions resolve right now, once, when
Inbox confirms the task exists. No retry, no state held between calls --
see DependencyPublisher's module docstring and README.md for why.
"""

from __future__ import annotations

import logging
from uuid import UUID

from mind.dependencies.publisher import DependencyPublisher
from mind.task_creation.reply import MatchedReply

logger = logging.getLogger(__name__)


class DependencyAdapter:
    def __init__(self, publisher: DependencyPublisher) -> None:
        self._publisher = publisher

    async def on_task_created(self, matched: MatchedReply) -> None:
        try:
            task_id = str(UUID(matched.task_id))
        except ValueError:
            logger.warning("dependencies: task_id %r is not a UUID, skipping", matched.task_id)
            return
        mentions = matched.request.dependency_mentions
        if not mentions:
            return
        await self._publisher.publish_dependencies(
            task_id=task_id, tenant_id=matched.tenant_id, dependency_mentions=list(mentions)
        )
