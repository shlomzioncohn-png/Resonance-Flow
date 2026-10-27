"""#918: the end-to-end scenario the issue asks for -- a dependency
mention left pending, a later task.created for the matching target
(from any source, not just Mind's own creation flow -- #918's review
point), and the edge gets published once that target is actually
indexed. Real Postgres (the pending store) and a real pgvector table
(so resolution is real, not mocked) -- only Kafka is faked.
"""

import os
from uuid import UUID

import pytest
from benchmarks.vector_store_test_table import create_vector_store_test_table

from mind.config import VectorStoreSettings
from mind.contracts.task_dependencies_proposed import DependencyProposed
from mind.dependencies.postgres_pending import PostgresPendingDependencyStore
from mind.dependencies.publisher import DependencyPublisher
from mind.dependencies.retry import retry_pending_dependencies
from mind.embeddings.base import DEMO_TENANT_ID
from mind.embeddings.index_consumer import handle_task_created
from mind.embeddings.pgvector_store import PgVectorStore
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent

TASK_A = UUID("3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
TASK_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"

# The exact mention/task text pair test_dependency_resolution.py already
# proves clears the real dependency_match_threshold -- reused here rather
# than inventing a new pair and hoping it's similar enough.
MENTION = "the DB upgrade"
TASK_B_TITLE = "Upgrade the production database"
TASK_B_DESCRIPTION = "Move to the new major version"


class ListSink:
    def __init__(self) -> None:
        self.sent: list[DependencyProposed] = []

    def send(self, payload: DependencyProposed) -> None:
        self.sent.append(payload)


@pytest.fixture
async def index():
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test_dep_retry",
    )
    await create_vector_store_test_table(settings)
    idx = PgVectorStore(settings)
    await idx.open()
    yield idx
    await idx.clear()
    await idx.close()


@pytest.fixture
async def pending():
    p = PostgresPendingDependencyStore(os.environ["MIND_VECTOR_STORE__DSN"])
    await p.open()
    yield p
    async with p._cursor() as cur:  # test-only cleanup, not part of the protocol
        await cur.execute("DELETE FROM pending_dependencies;")
    await p.close()


async def test_a_mention_left_pending_resolves_once_its_target_is_indexed(index, pending) -> None:
    sink = ListSink()
    publisher = DependencyPublisher(index=index, sink=sink, pending=pending)

    # Task A's mention doesn't resolve yet -- the store is still empty.
    await publisher.publish_dependencies(
        task_id=TASK_A, tenant_id=DEMO_TENANT_ID, dependency_mentions=[MENTION]
    )
    assert await pending.all_for_tenant(DEMO_TENANT_ID) == [(TASK_A, MENTION)]
    assert sink.sent == []

    # Task B -- the real target -- arrives and is indexed. #918: the retry
    # hook must fire for *any* task.created, including one Mind never
    # asked to be created, not only a reply to its own request.
    event = TaskCreatedEvent(
        id=TASK_B,
        tenant_id=str(DEMO_TENANT_ID),
        title=TASK_B_TITLE,
        description=TASK_B_DESCRIPTION,
    )

    async def on_indexed(task_id: str, tenant_id: str, _event: TaskCreatedEvent) -> None:
        await retry_pending_dependencies(pending, publisher, UUID(tenant_id))

    await handle_task_created(index, event, [on_indexed])

    [sent] = sink.sent
    assert sent.task_id == TASK_A
    assert sent.dependencies[0].depends_on_task_id == UUID(TASK_B)
    assert await pending.all_for_tenant(DEMO_TENANT_ID) == []


async def test_a_mention_with_no_matching_target_stays_pending(index, pending) -> None:
    """The retry must not invent a resolution, or discard a mention,
    just because *some* task got indexed -- only a real match does."""
    sink = ListSink()
    publisher = DependencyPublisher(index=index, sink=sink, pending=pending)

    await publisher.publish_dependencies(
        task_id=TASK_A, tenant_id=DEMO_TENANT_ID, dependency_mentions=[MENTION]
    )

    unrelated_event = TaskCreatedEvent(
        id="unrelated-task",
        tenant_id=str(DEMO_TENANT_ID),
        title="Plan the team offsite catering",
        description="Order food for the offsite",
    )

    async def on_indexed(task_id: str, tenant_id: str, _event: TaskCreatedEvent) -> None:
        await retry_pending_dependencies(pending, publisher, UUID(tenant_id))

    await handle_task_created(index, unrelated_event, [on_indexed])

    assert sink.sent == []
    assert await pending.all_for_tenant(DEMO_TENANT_ID) == [(TASK_A, MENTION)]
