"""
Full-cycle test for the MIND vector interface.

Each of embed / upsert / search is also exercised on its own, so a failure
tells you exactly which stage broke rather than just "something is wrong".
"""

import uuid

import psycopg2
import pytest

from mind.embeddings import embed, upsert, search
from mind.embeddings.config import PG_DSN, PG_TABLE
from mind.embeddings.factory import get_store


@pytest.fixture(autouse=True)
def clean_table():
    """Truncate the table before every test.

    Without this, leftover rows from an earlier test can legitimately
    out-rank a test's own data in a k-limited search -- especially with
    `hnsw.ef_search` tuned higher for better recall (see
    config.HNSW_EF_SEARCH), which makes search *more* likely to surface a
    genuinely-closer leftover row instead of quietly missing it.

    `get_store()` runs first because on a genuinely fresh database (a
    clean CI container, a first-time local setup) the table doesn't exist
    yet -- nothing has called it before -- and `TRUNCATE` fails with
    `UndefinedTable`. `get_store()` runs the same idempotent
    `CREATE TABLE/INDEX IF NOT EXISTS` schema setup the real store uses,
    so the table is guaranteed to exist before we try to empty it.
    """
    get_store()
    conn = psycopg2.connect(PG_DSN)
    with conn, conn.cursor() as cur:
        cur.execute(f"TRUNCATE TABLE {PG_TABLE};")
    conn.close()
    yield


def test_embed_is_independent():
    """embed() alone: correct shape, deterministic, no DB involved."""
    vec = embed("Fix the login bug on the payments page")
    assert isinstance(vec, list)
    assert len(vec) == 256
    assert all(isinstance(x, float) for x in vec)
    # Deterministic: same text -> same vector.
    assert vec == embed("Fix the login bug on the payments page")
    # Different text -> different vector.
    assert vec != embed("Completely unrelated marketing task")


def test_upsert_is_independent():
    """upsert() alone: doesn't raise, and the vector becomes findable."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Write release notes for v2.3")
    upsert(task_id, vec)  # should not raise
    # Searching for the exact vector should surface it.
    results = search(vec, k=5)
    assert task_id in results


def test_search_is_independent():
    """search() alone: given vectors already stored, returns similar ids ranked."""
    base_text = "Investigate slow database queries in the reports module"
    near_text = "Investigate slow database queries in the reporting module"
    far_text = "Order more coffee for the office kitchen"

    base_id, near_id, far_id = (f"test-{uuid.uuid4()}" for _ in range(3))
    upsert(base_id, embed(base_text))
    upsert(near_id, embed(near_text))
    upsert(far_id, embed(far_text))

    results = search(embed(base_text), k=3)
    assert base_id in results
    # The near-duplicate task should rank above the unrelated one.
    assert results.index(near_id) < results.index(far_id)


def test_full_cycle_embed_upsert_search():
    """
    Acceptance test: create an embedding, save it, then search and find it.
    """
    task_id = f"test-{uuid.uuid4()}"
    text = "Customer reported duplicate invoices being generated"

    vector = embed(text)
    assert len(vector) == 256

    upsert(task_id, vector)

    results = search(vector, k=1)
    assert results == [task_id]


def test_future_data_source_uses_same_interface():
    """
    Acceptance criterion: a future data source (GitHub issues, CRM tickets,
    etc.) must be able to use embed()/upsert() with zero redesign -- it's
    just another caller passing in text and an id, same as tasks today.
    """
    github_issue_id = f"github-issue-{uuid.uuid4()}"
    crm_ticket_id = f"crm-ticket-{uuid.uuid4()}"

    upsert(github_issue_id, embed("Null pointer exception in checkout.py line 42"))
    upsert(crm_ticket_id, embed("Customer asking for a refund on order #8831"))

    results = search(embed("Null pointer exception in checkout.py"), k=1)
    assert results == [github_issue_id]
