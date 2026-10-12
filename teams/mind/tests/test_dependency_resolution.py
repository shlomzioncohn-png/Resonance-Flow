"""#245: resolve_dependency_targets() against a real pgvector store --
needed for real similarity scores, which a fake store can't produce."""

import os

import psycopg
import pytest
from psycopg import sql

from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.dependency_resolution import resolve_dependency_targets
from mind.embeddings.embeddings import EMBEDDING_DIM
from mind.embeddings.pgvector_store import PgVectorStore


async def _create_test_table(settings: VectorStoreSettings) -> None:
    # PgVectorStore no longer creates its own schema (issue #441) -- the
    # real table is created by `alembic upgrade head`, which doesn't cover
    # this suite's own dedicated test table. Same pattern as
    # test_vector_store.py's helper of the same name.
    table = sql.Identifier(settings.table)
    async with await psycopg.AsyncConnection.connect(
        str(settings.dsn.get_secret_value()), autocommit=True
    ) as conn:
        await conn.execute("CREATE EXTENSION IF NOT EXISTS vector;")
        await conn.execute(
            sql.SQL(
                "CREATE TABLE IF NOT EXISTS {table} "
                "(task_id TEXT PRIMARY KEY, embedding VECTOR({dim}) NOT NULL, "
                "is_completed BOOLEAN NOT NULL DEFAULT FALSE, content_hash TEXT);"
            ).format(table=table, dim=sql.Literal(EMBEDDING_DIM))
        )
        await conn.execute(
            sql.SQL(
                "CREATE INDEX IF NOT EXISTS {idx} ON {table} USING hnsw (embedding vector_cosine_ops);"
            ).format(idx=sql.Identifier(f"{settings.table}_embedding_hnsw_idx"), table=table)
        )


@pytest.fixture(scope="module")
async def store():
    """Same pattern as test_vector_store.py's `store` fixture."""
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test_deps",
    )
    await _create_test_table(settings)
    s = PgVectorStore(settings)
    await s.open()
    yield s
    await s.close()


@pytest.fixture(autouse=True)
async def clean_store(store):
    await store.clear()
    yield


async def test_resolves_a_strong_match(store):
    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))

    [candidates] = await resolve_dependency_targets(
        store, ["the DB upgrade: upgrade the production database"]
    )

    assert candidates[0].task_id == "task-db-upgrade"
    assert candidates[0].similarity > 0.7


async def test_resolves_a_weak_match(store):
    await store.upsert("task-db-upgrade", embed("Upgrade the production database to the new version"))
    await store.upsert("task-unrelated", embed("Plan the team offsite catering order"))

    [strong] = await resolve_dependency_targets(
        store, ["the DB upgrade: upgrade the production database"]
    )
    [weak] = await resolve_dependency_targets(store, ["something about a database maybe"])

    assert weak[0].similarity < strong[0].similarity


async def test_no_match_when_the_store_is_empty(store):
    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"])
    assert candidates == []


async def test_resolves_multiple_references_independently_in_order(store):
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
