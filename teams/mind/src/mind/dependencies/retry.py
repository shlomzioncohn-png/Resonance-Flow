"""#918: retry every pending dependency mention for one tenant.

Meant to run from the index consumer's `on_indexed` hook (see
`embeddings/index_consumer.py`), after a task.created's row is already in
the index -- a mention can only resolve once its target is searchable,
and a retry that ran before that would find nothing, same as the first
attempt did.
"""

from __future__ import annotations

from collections import defaultdict
from uuid import UUID

from mind.dependencies.pending import PendingDependencyStore
from mind.dependencies.publisher import DependencyPublisher


async def retry_pending_dependencies(
    pending: PendingDependencyStore, publisher: DependencyPublisher, tenant_id: UUID
) -> None:
    """Group every mention still pending for `tenant_id` by its source
    task, and ask `publisher` to resolve each task's mentions again.
    `publish_dependencies` already discards a mention from `pending` the
    moment it resolves (first try or retry, no difference), so nothing
    here decides success or failure itself."""

    by_task: dict[UUID, list[str]] = defaultdict(list)
    for task_id, mention in await pending.all_for_tenant(tenant_id):
        by_task[task_id].append(mention)

    for task_id, mentions in by_task.items():
        await publisher.publish_dependencies(
            task_id=task_id, tenant_id=tenant_id, dependency_mentions=mentions
        )
