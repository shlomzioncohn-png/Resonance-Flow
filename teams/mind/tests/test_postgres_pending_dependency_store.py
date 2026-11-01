"""#918: `PostgresPendingDependencyStore` against a real Postgres -- the
same contract `PendingDependencies` satisfies in memory, plus the one
thing memory cannot: a pending mention is still there after a restart.
"""

import os
from uuid import UUID

import pytest

from mind.dependencies.postgres_pending import PostgresPendingDependencyStore

TASK_A = UUID("3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
TASK_B = UUID("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")
TENANT = UUID("7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a")
OTHER_TENANT = UUID("1a9c3e2b-5d4f-4a8e-9b1c-2d7e5b0c4a99")


@pytest.fixture
async def store(migrated_db):
    s = PostgresPendingDependencyStore(os.environ["MIND_VECTOR_STORE__DSN"])
    await s.open()
    yield s
    async with s._cursor() as cur:  # test-only cleanup, not part of the protocol
        await cur.execute("DELETE FROM pending_dependencies;")
    await s.close()


async def test_a_pending_mention_appears_in_all_for_tenant(store) -> None:
    await store.add(TASK_A, TENANT, "the DB upgrade")

    assert await store.all_for_tenant(TENANT) == [(TASK_A, "the DB upgrade")]


async def test_adding_the_same_mention_twice_is_not_a_duplicate(store) -> None:
    await store.add(TASK_A, TENANT, "the DB upgrade")
    await store.add(TASK_A, TENANT, "the DB upgrade")

    assert await store.all_for_tenant(TENANT) == [(TASK_A, "the DB upgrade")]


async def test_discard_removes_the_mention(store) -> None:
    await store.add(TASK_A, TENANT, "the DB upgrade")

    await store.discard(TASK_A, "the DB upgrade")

    assert await store.all_for_tenant(TENANT) == []


async def test_discard_is_a_noop_for_an_unknown_mention(store) -> None:
    await store.discard(TASK_A, "never added")  # must not raise


async def test_all_for_tenant_excludes_other_tenants(store) -> None:
    await store.add(TASK_A, TENANT, "the DB upgrade")
    await store.add(TASK_B, OTHER_TENANT, "the pricing bug")

    assert await store.all_for_tenant(TENANT) == [(TASK_A, "the DB upgrade")]


async def test_a_mention_survives_a_restart(store) -> None:
    """The actual point of this store over the in-memory one (#918): a
    second, independent instance sees the mention the first one wrote."""
    await store.add(TASK_A, TENANT, "the DB upgrade")

    restarted = PostgresPendingDependencyStore(os.environ["MIND_VECTOR_STORE__DSN"])
    await restarted.open()
    try:
        assert await restarted.all_for_tenant(TENANT) == [(TASK_A, "the DB upgrade")]
    finally:
        await restarted.close()
