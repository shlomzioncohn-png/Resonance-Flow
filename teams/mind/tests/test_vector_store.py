"""
Full-cycle test for the MIND vector interface.

Each of embed / upsert / search is also exercised on its own, so a failure
tells you exactly which stage broke rather than just "something is wrong".
"""

import asyncio
import uuid
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock

import pytest
from psycopg_pool import PoolTimeout

import mind.embeddings as mind_embeddings
from mind.embeddings import clear, delete, embed, search, upsert
from mind.embeddings.config import EMBEDDING_DIM, HNSW_EF_SEARCH
from mind.embeddings.pgvector_store import PgVectorStore


@pytest.fixture(scope="module", autouse=True)
async def _store_lifecycle():
    """Open the vector store's connection pool once for this whole test
    module, not once per test.

    Deliberately scoped to *this* module (not tests/conftest.py, which is
    shared by every test file) -- a DB-free test file like
    test_embeddings.py should never be forced to open a real connection
    just because it happens to share a conftest.py with this one.

    Matches how a real long-lived server uses the pool. Found during
    PR #140 review: with a fresh event loop per test (pytest-asyncio's
    default), the pool's background tasks (reconnect, min-size top-up,
    connection recycling) stayed pinned to the *first* test's loop, which
    was already closed by the second test -- tests still passed because
    the pool kept handing out connections it already held, but it was
    silently running half-dead from test 2 onward, and didn't recover
    from a simulated backend restart the way a single-loop server does.
    Requires `asyncio_default_fixture_loop_scope = "session"` in
    pyproject.toml so this fixture and every async test share one loop.
    """
    await mind_embeddings.open()
    yield
    await mind_embeddings.close()


@pytest.fixture(autouse=True)
async def clean_store():
    """Clear the (dedicated test) table before every test.

    Without this, leftover rows from an earlier test can legitimately
    out-rank a test's own data in a k-limited search -- especially with
    `hnsw.ef_search` tuned higher for better recall (see
    config.HNSW_EF_SEARCH), which makes search *more* likely to surface a
    genuinely-closer leftover row instead of quietly missing it.

    Goes through the public `clear()`, not raw SQL, so this file never
    needs to know Postgres is involved. Store initialization itself (the
    connection pool, schema) is handled once per module by
    `_store_lifecycle` above, not here.
    """
    await clear()
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


def test_embed_rejects_text_with_no_extractable_tokens():
    """Empty/punctuation-only text would hash to an all-zero vector, which
    makes pgvector's cosine-distance operator return NaN and silently
    corrupt search ranking -- embed() should refuse it instead."""
    for text in ("", "   ", "!!!"):
        with pytest.raises(ValueError):
            embed(text)


async def test_upsert_is_independent():
    """upsert() alone: doesn't raise, and the vector becomes findable."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Write release notes for v2.3")
    await upsert(task_id, vec)  # should not raise
    # Searching for the exact vector should surface it.
    result_ids = [r.task_id for r in await search(vec, k=5)]
    assert task_id in result_ids


async def test_search_is_independent():
    """search() alone: given vectors already stored, returns similar ids ranked."""
    base_text = "Investigate slow database queries in the reports module"
    near_text = "Investigate slow database queries in the reporting module"
    far_text = "Order more coffee for the office kitchen"

    base_id, near_id, far_id = (f"test-{uuid.uuid4()}" for _ in range(3))
    await upsert(base_id, embed(base_text))
    await upsert(near_id, embed(near_text))
    await upsert(far_id, embed(far_text))

    result_ids = [r.task_id for r in await search(embed(base_text), k=3)]
    assert base_id in result_ids
    # The near-duplicate task should rank above the unrelated one.
    assert result_ids.index(near_id) < result_ids.index(far_id)


async def test_search_returns_similarity_scores_in_descending_order():
    """search() results are SearchResult(task_id, similarity), best match
    first -- similarity comparable directly against
    config.DUPLICATE_SIMILARITY_THRESHOLD."""
    near_id, far_id = f"test-{uuid.uuid4()}", f"test-{uuid.uuid4()}"
    query_text = "Investigate slow database queries in the reports module"
    await upsert(near_id, embed("Investigate slow database queries in the reporting module"))
    await upsert(far_id, embed("Order more coffee for the office kitchen"))

    results = await search(embed(query_text), k=2)
    ids = [r.task_id for r in results]
    similarities = [r.similarity for r in results]

    assert ids.index(near_id) < ids.index(far_id)
    # Best match first: descending similarity.
    assert similarities == sorted(similarities, reverse=True)
    # Cosine similarity of unit vectors is bounded in [-1, 1] --
    # SearchResult.similarity itself enforces this range too.
    assert all(-1.0 <= s <= 1.0 for s in similarities)


async def test_search_respects_k_limit():
    """search(vec, k=2) must return at most 2 results, even with more
    than 2 genuine candidates in the table."""
    query_vec = embed("Investigate a reported bug in the payment flow")
    for i in range(5):
        await upsert(
            f"test-k-limit-{i}-{uuid.uuid4()}",
            embed(f"Investigate reported bug number {i} in the payment flow"),
        )

    results = await search(query_vec, k=2)
    assert len(results) <= 2


async def test_full_cycle_embed_upsert_search():
    """
    Acceptance test: create an embedding, save it, then search and find it.
    """
    task_id = f"test-{uuid.uuid4()}"
    text = "Customer reported duplicate invoices being generated"

    vector = embed(text)
    assert len(vector) == EMBEDDING_DIM

    await upsert(task_id, vector)

    results = await search(vector, k=1)
    assert [r.task_id for r in results] == [task_id]
    # Searching for the exact stored vector: similarity to itself is ~1.0.
    assert results[0].similarity == pytest.approx(1.0, abs=1e-6)


async def test_delete_removes_a_stored_vector():
    """delete() alone: the vector stops being findable afterward."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Retire the legacy export endpoint")
    await upsert(task_id, vec)
    assert task_id in [r.task_id for r in await search(vec, k=5)]

    await delete(task_id)

    assert task_id not in [r.task_id for r in await search(vec, k=5)]


async def test_delete_is_a_noop_for_an_unknown_task_id():
    """Deleting something that was never stored should not raise."""
    await delete(f"test-{uuid.uuid4()}")  # should not raise


async def test_future_data_source_uses_same_interface():
    """
    Acceptance criterion: a future data source (GitHub issues, CRM tickets,
    etc.) must be able to use embed()/upsert() with zero redesign -- it's
    just another caller passing in text and an id, same as tasks today.
    """
    github_issue_id = f"github-issue-{uuid.uuid4()}"
    crm_ticket_id = f"crm-ticket-{uuid.uuid4()}"

    await upsert(github_issue_id, embed("Null pointer exception in checkout.py line 42"))
    await upsert(crm_ticket_id, embed("Customer asking for a refund on order #8831"))

    results = await search(embed("Null pointer exception in checkout.py"), k=1)
    assert [r.task_id for r in results] == [github_issue_id]


async def test_concurrent_search_and_upsert_calls_all_succeed():
    """Fires several upsert()/search() calls at once via asyncio.gather()
    and asserts they all succeed -- exercises the pool under real
    concurrency (it provisions up to 5 connections; a test that only ever
    awaits one call at a time never uses more than one)."""
    task_ids = [f"test-concurrent-{i}-{uuid.uuid4()}" for i in range(5)]
    vectors = [embed(f"Concurrent test task number {i}") for i in range(5)]

    await asyncio.gather(*(upsert(tid, v) for tid, v in zip(task_ids, vectors)))

    results = await asyncio.gather(*(search(v, k=3) for v in vectors))
    assert len(results) == 5
    assert all(isinstance(r, list) and len(r) >= 1 for r in results)


async def test_search_raises_when_database_is_unreachable():
    """This module owns a connection pool now -- a genuinely unreachable
    database must surface as an error from search(), not hang forever or
    return a silently-empty result.

    Deliberately targets a closed *local* port (127.0.0.1:1, essentially
    guaranteed not to have anything listening), not an unroutable address
    like TEST-NET-1 (192.0.2.1) -- a closed port gets an immediate
    connection-refused from the OS, while an unroutable address can sit
    in the OS's own TCP retry/timeout handling for far longer than
    connect_timeout requests, making the test's runtime depend on
    platform networking behavior outside this module's control.
    """
    unreachable_store = PgVectorStore(
        dsn="dbname=doesnotexist host=127.0.0.1 port=1 connect_timeout=2",
        pool_timeout=5,
    )
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
    """Unit test (no real DB) exercising PgVectorStore.search() directly:
    asserts it actually issues a statement applying hnsw.ef_search before
    the SELECT.

    This replaces an earlier version of this test that opened its own
    connection, ran `SET LOCAL` itself, and asserted Postgres honored it
    -- which doesn't test our code at all (Postgres honoring SET LOCAL is
    a database feature, not something this codebase implements). That
    version would still have passed even if search() stopped applying
    ef_search entirely. This one wraps the cursor search() actually uses
    and inspects what search() itself sent, so it fails if that line is
    ever removed or broken -- which is exactly what caught this: the
    original SQL ("SET LOCAL hnsw.ef_search = %s") is invalid under
    psycopg3 (Postgres doesn't accept a bound parameter in a SET
    statement); see pgvector_store.py's search() for the set_config()
    fix and fuller explanation.
    """
    store = PgVectorStore.__new__(PgVectorStore)  # skip __init__: no real pool needed
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
    assert str(HNSW_EF_SEARCH) in ef_search_params
    assert data_query_calls, "search() never issued the actual data SELECT"
    assert ef_search_calls[0] < data_query_calls[0], "ef_search must be set before the data query"
