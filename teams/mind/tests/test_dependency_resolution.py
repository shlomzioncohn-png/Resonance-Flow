"""#245: resolve_dependency_targets() against a real pgvector store.

Real store, not a fake -- "strong match" vs "weak match" is about actual
similarity *scores*, which a fake store can't produce meaningfully (see
test_index_consumer.py's FakeVectorStore, which hardcodes similarity=1.0
for everything and is fine for #470's handler-logic tests, but not this).
"""

import os

import pytest

from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.dependency_resolution import resolve_dependency_targets
from mind.embeddings.pgvector_store import PgVectorStore


@pytest.fixture(scope="module")
async def store():
    """Same pattern as test_vector_store.py's `store` fixture -- a
    dedicated test table, scoped here (not conftest.py) so a DB-free test
    file never needs a real connection."""
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test_deps",
    )
    s = PgVectorStore(settings)
    await s.open()
    yield s
    await s.close()


@pytest.fixture(autouse=True)
async def clean_store(store):
    await store.clear()
    yield


async def test_resolves_a_strong_match(store):
    """The reference text is a near-exact restatement of a stored task --
    the top (and only meaningful) candidate should score high."""
    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))

    [candidates] = await resolve_dependency_targets(
        store, ["the DB upgrade: upgrade the production database"]
    )

    assert candidates[0].task_id == "task-db-upgrade"
    assert candidates[0].similarity > 0.7


async def test_resolves_a_weak_match(store):
    """The reference shares only a little vocabulary with the stored
    task -- still returned (no threshold applied here, that's #244's
    job), but clearly scored lower than a strong match."""
    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))
    await store.upsert("task-unrelated", embed("Plan the team offsite catering order"))

    [strong] = await resolve_dependency_targets(
        store, ["the DB upgrade: upgrade the production database"]
    )
    [weak] = await resolve_dependency_targets(store, ["something about a database maybe"])

    assert weak[0].similarity < strong[0].similarity


async def test_no_match_when_the_store_is_empty(store):
    """Nothing indexed yet -- an empty candidate list, not an error."""
    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"])
    assert candidates == []


async def test_resolves_multiple_references_independently_in_order(store):
    """A list of reference strings in -> one ranked list out per
    reference, same order -- the interface agreed with Yonit."""
    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))
    await store.upsert("task-release-notes", embed("Write release notes for v2.3"))

    results = await resolve_dependency_targets(
        store,
        [
            "the DB upgrade: upgrade the production database",
            "release notes: write release notes for v2.3",
        ],
    )

    assert len(results) == 2
    assert results[0][0].task_id == "task-db-upgrade"
    assert results[1][0].task_id == "task-release-notes"


async def test_propagates_embed_error_for_an_empty_reference(store):
    with pytest.raises(ValueError):
        await resolve_dependency_targets(store, [""])
