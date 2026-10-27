"""#918: `retry_pending_dependencies` groups pending mentions by their
source task and asks `DependencyPublisher` to resolve each task's
mentions again. The resolve/publish/discard logic itself is
`test_dependencies_publish.py`'s job -- this only has to prove the
grouping and that it calls through."""

from unittest.mock import AsyncMock
from uuid import UUID

from mind.dependencies.pending import PendingDependencies
from mind.dependencies.retry import retry_pending_dependencies

TASK_A = UUID("3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
TASK_B = UUID("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")
TENANT = UUID("7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a")
OTHER_TENANT = UUID("1a9c3e2b-5d4f-4a8e-9b1c-2d7e5b0c4a99")


async def test_nothing_pending_calls_the_publisher_zero_times() -> None:
    pending = PendingDependencies()
    publisher = AsyncMock()

    await retry_pending_dependencies(pending, publisher, TENANT)

    publisher.publish_dependencies.assert_not_awaited()


async def test_one_pending_mention_retries_its_task() -> None:
    pending = PendingDependencies()
    await pending.add(TASK_A, TENANT, "the DB upgrade")
    publisher = AsyncMock()

    await retry_pending_dependencies(pending, publisher, TENANT)

    publisher.publish_dependencies.assert_awaited_once_with(
        task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
    )


async def test_two_mentions_for_the_same_task_retry_together_in_one_call() -> None:
    pending = PendingDependencies()
    await pending.add(TASK_A, TENANT, "the DB upgrade")
    await pending.add(TASK_A, TENANT, "the pricing bug")
    publisher = AsyncMock()

    await retry_pending_dependencies(pending, publisher, TENANT)

    publisher.publish_dependencies.assert_awaited_once()
    _, kwargs = publisher.publish_dependencies.call_args
    assert kwargs["task_id"] == TASK_A
    assert set(kwargs["dependency_mentions"]) == {"the DB upgrade", "the pricing bug"}


async def test_mentions_from_different_tasks_retry_as_separate_calls() -> None:
    pending = PendingDependencies()
    await pending.add(TASK_A, TENANT, "the DB upgrade")
    await pending.add(TASK_B, TENANT, "the pricing bug")
    publisher = AsyncMock()

    await retry_pending_dependencies(pending, publisher, TENANT)

    assert publisher.publish_dependencies.await_count == 2


async def test_only_the_given_tenants_mentions_retry() -> None:
    pending = PendingDependencies()
    await pending.add(TASK_A, TENANT, "the DB upgrade")
    await pending.add(TASK_B, OTHER_TENANT, "the pricing bug")
    publisher = AsyncMock()

    await retry_pending_dependencies(pending, publisher, TENANT)

    publisher.publish_dependencies.assert_awaited_once_with(
        task_id=TASK_A, tenant_id=TENANT, dependency_mentions=["the DB upgrade"]
    )
