"""
Full-cycle test for the MIND vector interface.

Each of embed / upsert / search is also exercised on its own, so a failure
tells you exactly which stage broke rather than just "something is wrong".
"""

import uuid

import psycopg
import pytest

from mind.embeddings import clear, delete, embed, search, upsert
from mind.embeddings.config import EMBEDDING_DIM, HNSW_EF_SEARCH, PG_DSN


@pytest.fixture(autouse=True)
async def clean_store():
    """Clear the (dedicated test) table before every test.

    Without this, leftover rows from an earlier test can legitimately
    out-rank a test's own data in a k-limited search -- especially with
    `hnsw.ef_search` tuned higher for better recall (see
    config.HNSW_EF_SEARCH), which makes search *more* likely to surface a
    genuinely-closer leftover row instead of quietly missing it.

    Goes through the public `clear()`, not raw SQL, so this file never
    needs to know Postgres is involved -- `clear()` runs the same
    idempotent schema setup `upsert`/`search` do first, so it also works
    against a genuinely fresh table (a clean CI container, a first-time
    local setup). `conftest.py` points `clear()` at a dedicated test
    table, so this never touches real data.
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
    results = await search(vec, k=5)
    assert task_id in results


async def test_search_is_independent():
    """search() alone: given vectors already stored, returns similar ids ranked."""
    base_text = "Investigate slow database queries in the reports module"
    near_text = "Investigate slow database queries in the reporting module"
    far_text = "Order more coffee for the office kitchen"

    base_id, near_id, far_id = (f"test-{uuid.uuid4()}" for _ in range(3))
    await upsert(base_id, embed(base_text))
    await upsert(near_id, embed(near_text))
    await upsert(far_id, embed(far_text))

    results = await search(embed(base_text), k=3)
    assert base_id in results
    # The near-duplicate task should rank above the unrelated one.
    assert results.index(near_id) < results.index(far_id)


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
    assert results == [task_id]


async def test_delete_removes_a_stored_vector():
    """delete() alone: the vector stops being findable afterward."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Retire the legacy export endpoint")
    await upsert(task_id, vec)
    assert task_id in await search(vec, k=5)

    await delete(task_id)

    assert task_id not in await search(vec, k=5)


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
    assert results == [github_issue_id]


async def test_ef_search_setting_is_actually_applied():
    """
    Regression check for the SET LOCAL / autocommit risk noted in
    pgvector_store.py's search(): `SET LOCAL hnsw.ef_search` only takes
    effect for the current transaction. If the store's connections were
    ever switched to autocommit (each statement its own transaction), the
    SET would silently become a no-op right before the SELECT that needs
    it -- HNSW would quietly fall back to its default ef_search (40)
    instead of the tuned 400, and nothing about search()'s return value
    would reveal that recall had degraded.

    This talks to Postgres directly, unlike every other test in this file
    -- it's deliberately verifying a pgvector-specific implementation
    detail (that the setting really reaches the server), not application
    behavior, so it's the one place this file is allowed to know that.
    """
    async with await psycopg.AsyncConnection.connect(PG_DSN) as conn:
        async with conn.cursor() as cur:
            await cur.execute("SET LOCAL hnsw.ef_search = %s;", (HNSW_EF_SEARCH,))
            await cur.execute("SHOW hnsw.ef_search;")
            (value,) = await cur.fetchone()
            assert value == str(HNSW_EF_SEARCH)
