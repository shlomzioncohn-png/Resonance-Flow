"""Full-cycle test for the MIND vector interface. embed/upsert/search
are also exercised individually, so a failure pinpoints the stage."""

import asyncio
import os
import uuid
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock

import psycopg
import pytest
from psycopg import sql
from psycopg_pool import PoolTimeout

from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.embeddings import EMBEDDING_DIM
from mind.embeddings.pgvector_store import PgVectorStore


async def _create_test_table(settings: VectorStoreSettings) -> None:
    # The real table is created by `alembic upgrade head`; this suite's
    # dedicated test table isn't covered by that, so it sets itself up here.
    table = sql.Identifier(settings.table)
    async with await psycopg.AsyncConnection.connect(
        str(settings.dsn.get_secret_value()), autocommit=True
    ) as conn:
        await conn.execute("CREATE EXTENSION IF NOT EXISTS vector;")
        await conn.execute(
            sql.SQL(
                "CREATE TABLE IF NOT EXISTS {table} "
                "(task_id TEXT PRIMARY KEY, embedding VECTOR({dim}) NOT NULL);"
            ).format(table=table, dim=sql.Literal(EMBEDDING_DIM))
        )
        await conn.execute(
            sql.SQL(
                "CREATE INDEX IF NOT EXISTS {idx} ON {table} USING hnsw (embedding vector_cosine_ops);"
            ).format(idx=sql.Identifier(f"{settings.table}_embedding_hnsw_idx"), table=table)
        )


@pytest.fixture(scope="module")
async def store():
    """One store per module, not per test. Scoped here, not conftest.py,
    so DB-free test files never need a connection."""
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test",
    )
    await _create_test_table(settings)
    s = PgVectorStore(settings)
    await s.open()
    yield s
    await s.close()


@pytest.fixture(autouse=True)
async def clean_store(store):
    """Clear before every test -- leftover rows can out-rank new ones."""
    await store.clear()
    yield


async def test_embed_is_independent():
    """embed() alone: correct shape, deterministic, no DB involved."""
    vec = embed("Fix the login bug on the payments page")
    assert isinstance(vec, list)
    assert len(vec) == EMBEDDING_DIM
    assert all(isinstance(x, float) for x in vec)
    assert vec == embed("Fix the login bug on the payments page")
    assert vec != embed("Completely unrelated marketing task")


def test_embed_rejects_empty_or_whitespace_only_text():
    """Empty/whitespace text has nothing meaningful to embed."""
    for text in ("", "   "):
        with pytest.raises(ValueError):
            embed(text)


async def test_upsert_is_independent(store):
    """upsert() alone: doesn't raise, and the vector becomes findable."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Write release notes for v2.3")
    await store.upsert(task_id, vec)  # should not raise
    result_ids = [r.task_id for r in await store.search(vec, k=5)]
    assert task_id in result_ids


async def test_search_is_independent(store):
    """search() alone: given vectors already stored, returns similar ids ranked."""
    base_text = "Investigate slow database queries in the reports module"
    near_text = "Investigate slow database queries in the reporting module"
    far_text = "Order more coffee for the office kitchen"

    base_id, near_id, far_id = (f"test-{uuid.uuid4()}" for _ in range(3))
    await store.upsert(base_id, embed(base_text))
    await store.upsert(near_id, embed(near_text))
    await store.upsert(far_id, embed(far_text))

    result_ids = [r.task_id for r in await store.search(embed(base_text), k=3)]
    assert base_id in result_ids
    assert result_ids.index(near_id) < result_ids.index(far_id)


async def test_search_returns_similarity_scores_in_descending_order(store):
    """Best match first, descending similarity."""
    near_id, far_id = f"test-{uuid.uuid4()}", f"test-{uuid.uuid4()}"
    query_text = "Investigate slow database queries in the reports module"
    await store.upsert(near_id, embed("Investigate slow database queries in the reporting module"))
    await store.upsert(far_id, embed("Order more coffee for the office kitchen"))

    results = await store.search_text(query_text, k=2)
    ids = [r.task_id for r in results]
    similarities = [r.similarity for r in results]

    assert ids.index(near_id) < ids.index(far_id)
    assert similarities == sorted(similarities, reverse=True)
    assert all(-1.0 <= s <= 1.0 for s in similarities)


async def test_search_text_embeds_as_a_query_not_a_passage(store):
    """search_text() must use is_query=True internally."""
    task_id = f"test-{uuid.uuid4()}"
    text = "Investigate slow database queries in the reports module"
    await store.upsert(task_id, embed(text))

    via_search_text = (await store.search_text(text, k=1))[0].similarity
    via_raw_search = (await store.search(embed(text), k=1))[0].similarity

    assert via_search_text != via_raw_search


async def test_search_respects_k_limit(store):
    """At most k results, even with more genuine candidates."""
    query_vec = embed("Investigate a reported bug in the payment flow")
    for i in range(5):
        await store.upsert(
            f"test-k-limit-{i}-{uuid.uuid4()}",
            embed(f"Investigate reported bug number {i} in the payment flow"),
        )

    results = await store.search(query_vec, k=2)
    assert len(results) == 2


async def test_full_cycle_embed_upsert_search(store):
    """Create, save, search, find."""
    task_id = f"test-{uuid.uuid4()}"
    text = "Customer reported duplicate invoices being generated"

    vector = embed(text)
    assert len(vector) == EMBEDDING_DIM

    await store.upsert(task_id, vector)

    results = await store.search(vector, k=1)
    assert [r.task_id for r in results] == [task_id]
    assert results[0].similarity == pytest.approx(1.0, abs=1e-6)


async def test_delete_removes_a_stored_vector(store):
    """delete() alone: the vector stops being findable afterward."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Retire the legacy export endpoint")
    await store.upsert(task_id, vec)
    assert task_id in [r.task_id for r in await store.search(vec, k=5)]

    await store.delete(task_id)

    assert task_id not in [r.task_id for r in await store.search(vec, k=5)]


async def test_delete_is_a_noop_for_an_unknown_task_id(store):
    """Deleting something that was never stored should not raise."""
    await store.delete(f"test-{uuid.uuid4()}")  # should not raise


async def test_future_data_source_uses_same_interface(store):
    """A future data source uses embed()/upsert() with zero redesign."""
    github_issue_id = f"github-issue-{uuid.uuid4()}"
    crm_ticket_id = f"crm-ticket-{uuid.uuid4()}"

    await store.upsert(github_issue_id, embed("Null pointer exception in checkout.py line 42"))
    await store.upsert(crm_ticket_id, embed("Customer asking for a refund on order #8831"))

    results = await store.search_text("Null pointer exception in checkout.py", k=1)
    assert [r.task_id for r in results] == [github_issue_id]


async def test_concurrent_search_and_upsert_calls_all_succeed(store):
    """Several upsert()/search() calls at once all succeed."""
    task_ids = [f"test-concurrent-{i}-{uuid.uuid4()}" for i in range(5)]
    vectors = [embed(f"Concurrent test task number {i}") for i in range(5)]

    await asyncio.gather(*(store.upsert(tid, v) for tid, v in zip(task_ids, vectors)))

    results = await asyncio.gather(*(store.search(v, k=3) for v in vectors))
    assert len(results) == 5
    assert all(isinstance(r, list) and len(r) >= 1 for r in results)


async def test_search_raises_when_database_is_unreachable():
    """An unreachable DB surfaces as an error, not a hang."""
    unreachable_settings = VectorStoreSettings(
        dsn="postgresql://doesnotexist:doesnotexist@127.0.0.1:1/doesnotexist?connect_timeout=2",
        table="task_embeddings_test",
    )
    unreachable_store = PgVectorStore(unreachable_settings, pool_timeout=5)
    try:
        with pytest.raises(PoolTimeout):
            await unreachable_store.search([0.0] * EMBEDDING_DIM, k=1)
    finally:
        await unreachable_store.close()  # the pool opened before timing out


@asynccontextmanager
async def _fake_async_ctx(value):
    yield value


async def test_search_issues_set_local_ef_search_before_the_select():
    """Mocked cursor: search() sets hnsw.ef_search before the data query."""
    store = PgVectorStore.__new__(PgVectorStore)  # skip __init__: no real pool needed
    store._settings = VectorStoreSettings(
        dsn="postgresql://u:p@h:5432/d", table="task_embeddings_test", search_effort=400
    )
    store._ready = True  # skip _ensure_ready(): no real connection needed

    mock_cursor = AsyncMock()
    mock_cursor.fetchall.return_value = [("task-1", 0.9)]

    mock_conn = MagicMock()
    mock_conn.cursor = MagicMock(side_effect=lambda: _fake_async_ctx(mock_cursor))

    store._pool = MagicMock()
    store._pool.connection = MagicMock(side_effect=lambda: _fake_async_ctx(mock_conn))

    results = await store.search([0.1, 0.2], k=5)

    assert len(results) == 1
    assert results[0].task_id == "task-1"

    calls = mock_cursor.execute.call_args_list
    executed_sql = [str(call.args[0]) for call in calls]
    ef_search_calls = [i for i, sql in enumerate(executed_sql) if "hnsw.ef_search" in sql]
    # "FROM" not "SELECT" -- set_config() is itself a SELECT.
    data_query_calls = [i for i, sql in enumerate(executed_sql) if "FROM" in sql.upper()]

    assert ef_search_calls, "search() never issued a statement mentioning hnsw.ef_search"
    # Bound param, not interpolated into the SQL text.
    ef_search_params = calls[ef_search_calls[0]].args[1]
    assert str(store._settings.search_effort) in ef_search_params
    assert data_query_calls, "search() never issued the actual data SELECT"
    assert ef_search_calls[0] < data_query_calls[0], "ef_search must be set before the data query"


async def test_search_query_can_use_the_hnsw_index(store):
    """ORDER BY must use the raw distance expression, or Postgres falls
    back to a full table scan instead of the HNSW index."""
    async with store._pool.connection() as conn, conn.cursor() as cur:
        await cur.execute("SET LOCAL enable_seqscan = off;")
        await cur.execute(
            sql.SQL("EXPLAIN {}").format(store._search_query()),
            ([0.0] * EMBEDDING_DIM, [0.0] * EMBEDDING_DIM, 1),
        )
        plan = "\n".join(row[0] for row in await cur.fetchall())
    assert "Index Scan" in plan, f"query plan did not use the index:\n{plan}"
