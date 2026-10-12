"""#245: resolve_dependency_targets() against a real pgvector store --
needed for real similarity scores, which a fake store can't produce."""

import os

import pytest
from benchmarks.vector_store_test_table import create_vector_store_test_table

from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.dependency_resolution import resolve_dependency_targets
from mind.embeddings.pgvector_store import PgVectorStore


@pytest.fixture(scope="module")
async def store():
    """Same pattern as test_vector_store.py's `store` fixture."""
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test_deps",
    )
    await create_vector_store_test_table(settings)
    s = PgVectorStore(settings)
    await s.open()
    yield s
    await s.close()


@pytest.fixture(autouse=True)
async def clean_store(store):
    await store.clear()
    yield


async def test_resolves_a_strong_match(store):
    """Realistic short mention, not a copy of the task's own words."""
    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))

    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"])

    assert candidates[0].task_id == "task-db-upgrade"


async def test_resolves_a_weak_match(store):
    """A vaguer mention still resolves to the right task, lower score."""
    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))
    await store.upsert("task-unrelated", embed("Plan the team offsite catering order"))

    [strong] = await resolve_dependency_targets(store, ["the DB upgrade"])
    [weak] = await resolve_dependency_targets(store, ["something about a database maybe"])

    assert strong[0].task_id == "task-db-upgrade"
    assert weak[0].task_id == "task-db-upgrade"
    assert weak[0].similarity < strong[0].similarity


async def test_no_match_when_the_store_is_empty(store):
    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"])
    assert candidates == []


async def test_no_strong_match_when_the_store_has_only_unrelated_tasks(store):
    """Unrelated store scores lower than one with the real match."""
    await store.upsert("task-catering", embed("Plan the team offsite catering order"))
    await store.upsert("task-standup-notes", embed("Write up notes from yesterday's standup"))

    [unmatched] = await resolve_dependency_targets(store, ["the DB upgrade"])

    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))
    [matched] = await resolve_dependency_targets(store, ["the DB upgrade"])

    assert matched[0].task_id == "task-db-upgrade"
    assert unmatched[0].similarity < matched[0].similarity


async def test_resolves_multiple_references_independently_in_order(store):
    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))
    await store.upsert("task-release-notes", embed("Write release notes for v2.3"))

    results = await resolve_dependency_targets(store, ["the DB upgrade", "release notes for v2.3"])

    assert len(results) == 2
    assert results[0][0].task_id == "task-db-upgrade"
    assert results[1][0].task_id == "task-release-notes"


async def test_propagates_embed_error_for_an_empty_reference(store):
    with pytest.raises(ValueError):
        await resolve_dependency_targets(store, [""])
