"""#355: the on_matched adapter plugging DependencyPublisher into
TaskCreationFlow.

Pass `.on_task_created` as `TaskCreationFlow`'s `on_matched`: publishes
whatever of a task's dependency mentions resolve right now, when Inbox
confirms the task exists. A mention that doesn't resolve yet is not lost
(#918): if `publisher` was given a `pending` store, it registers the
mention there, and the index consumer's `on_indexed` hook retries it
later -- see `publisher.py`'s module docstring and README.md.
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
            task_id = UUID(matched.task_id)
        except ValueError:
            logger.warning("dependencies: task_id %r is not a UUID, skipping", matched.task_id)
            return
        mentions = matched.request.dependency_mentions
        if not mentions:
            return
        await self._publisher.publish_dependencies(
            task_id=task_id, tenant_id=matched.tenant_id, dependency_mentions=list(mentions)
        )
