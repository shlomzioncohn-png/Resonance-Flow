"""
Full-cycle test for the MIND vector interface.

Each of embed / upsert / search is also exercised on its own, so a failure
tells you exactly which stage broke rather than just "something is wrong".
"""

import asyncio
import os
import uuid
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock

import pytest
from psycopg import sql
from psycopg_pool import PoolTimeout

from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.embeddings import EMBEDDING_DIM
from mind.embeddings.pgvector_store import PgVectorStore


@pytest.fixture(scope="module")
async def store():
    """One explicitly-constructed store for this whole test module, not
    one per test -- matches how a real server uses it (see
    `api/main.py`'s lifespan). Scoped here rather than conftest.py so a
    DB-free file like test_embeddings.py never needs a real connection.
    Requires `asyncio_default_fixture_loop_scope = "session"` in
    pyproject.toml so this fixture and every async test share one loop.
    """
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test",
    )
    s = PgVectorStore(settings)
    await s.open()
    yield s
    await s.close()


@pytest.fixture(autouse=True)
async def clean_store(store):
    """Clear the (dedicated test) table before every test -- otherwise
    leftover rows from an earlier test can out-rank a test's own data in
    a k-limited search."""
    await store.clear()
    yield


async def test_embed_is_independent():
    """embed() alone: correct shape, deterministic, no DB involved."""
    vec = embed("Fix the login bug on the payments page")
    assert isinstance(vec, list)
    assert len(vec) == EMBEDDING_DIM
    assert all(isinstance(x, float) for x in vec)
    # Deterministic: same text -> same vector.
    assert vec == embed("Fix the login bug on the payments page")
    # Different text -> different vector.
    assert vec != embed("Completely unrelated marketing task")


def test_embed_rejects_empty_or_whitespace_only_text():
    """A task with no title or description has nothing meaningful to
    index -- embed() refuses it as a semantic guard, not a model
    limitation (BGE happily embeds punctuation-only text like "!!!";
    unlike the earlier HashingVectorizer placeholder, that's no longer
    an all-zero/NaN-cosine-distance risk, so it's not rejected)."""
    for text in ("", "   "):
        with pytest.raises(ValueError):
            embed(text)


async def test_upsert_is_independent(store):
    """upsert() alone: doesn't raise, and the vector becomes findable."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Write release notes for v2.3")
    await store.upsert(task_id, vec)  # should not raise
    # Searching for the exact vector should surface it.
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
    # The near-duplicate task should rank above the unrelated one.
    assert result_ids.index(near_id) < result_ids.index(far_id)


async def test_search_returns_similarity_scores_in_descending_order(store):
    """search() results are SearchResult(task_id, similarity), best match
    first -- similarity comparable directly against
    Settings.duplicate_similarity_threshold."""
    near_id, far_id = f"test-{uuid.uuid4()}", f"test-{uuid.uuid4()}"
    query_text = "Investigate slow database queries in the reports module"
    await store.upsert(near_id, embed("Investigate slow database queries in the reporting module"))
    await store.upsert(far_id, embed("Order more coffee for the office kitchen"))

    results = await store.search(embed(query_text), k=2)
    ids = [r.task_id for r in results]
    similarities = [r.similarity for r in results]

    assert ids.index(near_id) < ids.index(far_id)
    # Best match first: descending similarity.
    assert similarities == sorted(similarities, reverse=True)
    # Cosine similarity of unit vectors is bounded in [-1, 1] --
    # SearchResult.similarity itself enforces this range too.
    assert all(-1.0 <= s <= 1.0 for s in similarities)


async def test_search_respects_k_limit(store):
    """search(vec, k=2) must return at most 2 results, even with more
    than 2 genuine candidates in the table."""
    query_vec = embed("Investigate a reported bug in the payment flow")
    for i in range(5):
        await store.upsert(
            f"test-k-limit-{i}-{uuid.uuid4()}",
            embed(f"Investigate reported bug number {i} in the payment flow"),
        )

    results = await store.search(query_vec, k=2)
    assert len(results) == 2


async def test_full_cycle_embed_upsert_search(store):
    """
    Acceptance test: create an embedding, save it, then search and find it.
    """
    task_id = f"test-{uuid.uuid4()}"
    text = "Customer reported duplicate invoices being generated"

    vector = embed(text)
    assert len(vector) == EMBEDDING_DIM

    await store.upsert(task_id, vector)

    results = await store.search(vector, k=1)
    assert [r.task_id for r in results] == [task_id]
    # Searching for the exact stored vector: similarity to itself is ~1.0.
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
    """
    Acceptance criterion: a future data source (GitHub issues, CRM tickets,
    etc.) must be able to use embed()/upsert() with zero redesign -- it's
    just another caller passing in text and an id, same as tasks today.
    """
    github_issue_id = f"github-issue-{uuid.uuid4()}"
    crm_ticket_id = f"crm-ticket-{uuid.uuid4()}"

    await store.upsert(github_issue_id, embed("Null pointer exception in checkout.py line 42"))
    await store.upsert(crm_ticket_id, embed("Customer asking for a refund on order #8831"))

    results = await store.search(embed("Null pointer exception in checkout.py"), k=1)
    assert [r.task_id for r in results] == [github_issue_id]


async def test_concurrent_search_and_upsert_calls_all_succeed(store):
    """Fires several upsert()/search() calls at once via asyncio.gather()
    and asserts they all succeed -- exercises the pool under real
    concurrency (it provisions up to 5 connections; a test that only ever
    awaits one call at a time never uses more than one)."""
    task_ids = [f"test-concurrent-{i}-{uuid.uuid4()}" for i in range(5)]
    vectors = [embed(f"Concurrent test task number {i}") for i in range(5)]

    await asyncio.gather(*(store.upsert(tid, v) for tid, v in zip(task_ids, vectors)))

    results = await asyncio.gather(*(store.search(v, k=3) for v in vectors))
    assert len(results) == 5
    assert all(isinstance(r, list) and len(r) >= 1 for r in results)


async def test_search_raises_when_database_is_unreachable():
    """A genuinely unreachable database must surface as an error from
    search(), not hang forever or return a silently-empty result. Targets
    a closed local port rather than an unroutable address (e.g. TEST-NET-1)
    so the OS fails the connection immediately instead of retrying past
    connect_timeout.
    """
    unreachable_settings = VectorStoreSettings(
        dsn="postgresql://doesnotexist:doesnotexist@127.0.0.1:1/doesnotexist?connect_timeout=2",
        table="task_embeddings_test",
    )
    unreachable_store = PgVectorStore(unreachable_settings, pool_timeout=5)
    try:
        with pytest.raises(PoolTimeout):
            await unreachable_store.search([0.0] * EMBEDDING_DIM, k=1)
    finally:
        # search() got far enough to open the pool before its connection
        # attempt timed out -- close it explicitly, or its background
        # tasks linger past this test and can stall process shutdown.
        await unreachable_store.close()


@asynccontextmanager
async def _fake_async_ctx(value):
    yield value


async def test_search_issues_set_local_ef_search_before_the_select():
    """Unit test (no real DB): mocks the cursor and asserts search() itself
    issues a statement applying hnsw.ef_search before the data query, so
    the assertion fails if that call is ever removed or broken -- see
    pgvector_store.py's search() for why it's set_config(), not SET LOCAL.
    """
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
    # "FROM", not "SELECT" -- set_config() is itself issued as a SELECT
    # (SELECT set_config(...)), so a bare "SELECT" check would match both
    # statements and land on the same index for each.
    data_query_calls = [i for i, sql in enumerate(executed_sql) if "FROM" in sql.upper()]

    assert ef_search_calls, "search() never issued a statement mentioning hnsw.ef_search"
    # The value is a bound parameter (call.args[1]), not interpolated into
    # the SQL text -- that's the fix (set_config, not "SET LOCAL ... %s"),
    # so it must NOT show up in the SQL string itself.
    ef_search_params = calls[ef_search_calls[0]].args[1]
    assert str(store._settings.search_effort) in ef_search_params
    assert data_query_calls, "search() never issued the actual data SELECT"
    assert ef_search_calls[0] < data_query_calls[0], "ef_search must be set before the data query"


async def test_search_query_can_use_the_hnsw_index(store):
    """search()'s ORDER BY must be the raw distance expression, not a
    computed "similarity" alias -- otherwise Postgres can't match it to the
    HNSW index and falls back to a full table scan (the actual regression
    this catches). `enable_seqscan = off` forces Postgres to prove the
    query SHAPE is index-compatible even on this suite's tiny table, where
    a scan and an index scan would otherwise return identical results and
    hide the bug."""
    async with store._pool.connection() as conn, conn.cursor() as cur:
        await cur.execute("SET LOCAL enable_seqscan = off;")
        await cur.execute(
            sql.SQL("EXPLAIN {}").format(store._search_query()),
            ([0.0] * EMBEDDING_DIM, [0.0] * EMBEDDING_DIM, 1),
        )
        plan = "\n".join(row[0] for row in await cur.fetchall())
    assert "Index Scan" in plan, f"query plan did not use the index:\n{plan}"
