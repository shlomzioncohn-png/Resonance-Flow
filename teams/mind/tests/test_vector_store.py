"""Full-cycle test for the MIND vector interface. embed/upsert/search
are also exercised individually, so a failure pinpoints the stage."""

import asyncio
import os
import uuid
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock

import pytest
from benchmarks.vector_store_test_table import create_vector_store_test_table
from psycopg import sql
from psycopg_pool import PoolTimeout

from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.embeddings import EMBEDDING_DIM
from mind.embeddings.pgvector_store import PgVectorStore

TENANT = uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
OTHER_TENANT = uuid.UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")


@pytest.fixture(scope="module")
async def index(migrated_db):
    """One store per module, not per test. Scoped here, not conftest.py,
    so DB-free test files never need a connection. Depends on
    migrated_db (conftest.py) so `alembic upgrade head` actually runs
    against the real `task_embeddings` table before any DB test does --
    this suite's own `task_embeddings_test` table isn't covered by that
    migration, so it still sets itself up separately below."""
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test",
    )
    await create_vector_store_test_table(settings)
    s = PgVectorStore(settings)
    await s.open()
    yield s
    await s.close()


@pytest.fixture
def store(index):
    return index.for_tenant(TENANT)


@pytest.fixture
def other_store(index):
    return index.for_tenant(OTHER_TENANT)


@pytest.fixture(autouse=True)
async def clean_store(index):
    """Clear before every test -- leftover rows can out-rank new ones."""
    await index.clear()
    yield


async def _insert_without_tenant(index, task_id, vector):
    """A row as written before #841: no tenant_id."""
    async with index._pool.connection() as conn:
        await conn.execute(
            sql.SQL("INSERT INTO {table} (task_id, embedding) VALUES (%s, %s);").format(
                table=index._table()
            ),
            (task_id, vector),
        )


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


async def test_upsert_if_absent_inserts_a_new_vector(store):
    """upsert_if_absent() on an unknown task_id inserts and returns True."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Draft the quarterly roadmap review")
    inserted = await store.upsert_if_absent(task_id, vec)
    assert inserted is True
    assert task_id in [r.task_id for r in await store.search(vec, k=5)]


async def test_upsert_if_absent_does_not_overwrite_an_existing_vector(store):
    """upsert_if_absent() on an existing task_id is a no-op (False)."""
    task_id = f"test-{uuid.uuid4()}"
    original_vec = embed("Original task description")
    await store.upsert(task_id, original_vec)

    other_vec = embed("A completely different description")
    inserted = await store.upsert_if_absent(task_id, other_vec)
    assert inserted is False

    results = await store.search(original_vec, k=1)
    assert results[0].task_id == task_id
    assert results[0].similarity == pytest.approx(1.0, abs=1e-6)


async def test_mark_completed_marks_an_existing_task(store):
    """mark_completed() flips is_completed on the row search() returns."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Ship the release notes")
    await store.upsert(task_id, vec)

    marked = await store.mark_completed(task_id)
    assert marked is True

    results = await store.search(vec, k=1)
    assert results[0].task_id == task_id
    assert results[0].is_completed is True


async def test_mark_completed_is_a_noop_for_an_unknown_task_id(store):
    """Unknown task_id returns False, not an error."""
    marked = await store.mark_completed(f"test-{uuid.uuid4()}")
    assert marked is False


async def test_mark_incomplete_resets_an_existing_task(store):
    """mark_incomplete() flips is_completed back off (e.g. a reopened task)."""
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Ship the release notes")
    await store.upsert(task_id, vec)
    await store.mark_completed(task_id)

    marked = await store.mark_incomplete(task_id)
    assert marked is True

    results = await store.search(vec, k=1)
    assert results[0].task_id == task_id
    assert results[0].is_completed is False


async def test_mark_incomplete_is_a_noop_for_an_unknown_task_id(store):
    """Unknown task_id returns False, not an error."""
    marked = await store.mark_incomplete(f"test-{uuid.uuid4()}")
    assert marked is False


async def test_get_content_hash_round_trips_through_upsert(store):
    task_id = f"test-{uuid.uuid4()}"
    await store.upsert(task_id, embed("Ship the release notes"), content_hash="abc123")
    assert await store.get_content_hash(task_id) == "abc123"


async def test_get_content_hash_is_none_for_an_unknown_task_id(store):
    assert await store.get_content_hash(f"test-{uuid.uuid4()}") is None


async def test_get_vector_round_trips_through_upsert(store):
    task_id = f"test-{uuid.uuid4()}"
    vec = embed("Ship the release notes")
    await store.upsert(task_id, vec)

    stored = await store.get_vector(task_id)

    assert stored is not None
    assert len(stored) == EMBEDDING_DIM
    assert all(abs(a - b) < 1e-5 for a, b in zip(stored, vec))


async def test_get_vector_is_none_for_an_unknown_task_id(store):
    assert await store.get_vector(f"test-{uuid.uuid4()}") is None


async def test_search_exclude_task_id_omits_the_given_task(store):
    query_vec = embed("Ship the release notes for v2.3")
    self_id, other_id = f"test-{uuid.uuid4()}", f"test-{uuid.uuid4()}"
    await store.upsert(self_id, query_vec)
    await store.upsert(other_id, embed("Write up the v2.3 release notes"))

    results = await store.search(query_vec, k=5, exclude_task_id=self_id)

    assert self_id not in [r.task_id for r in results]


async def test_search_exclude_task_id_does_not_let_self_consume_the_k_budget(store):
    """Same k-starvation risk as exclude_completed, for a single id."""
    query_vec = embed("Ship the release notes for v2.3")
    self_id, other_id = f"test-{uuid.uuid4()}", f"test-{uuid.uuid4()}"
    await store.upsert(self_id, query_vec)
    await store.upsert(other_id, embed("Write up the v2.3 release notes"))

    results = await store.search(query_vec, k=1, exclude_task_id=self_id)

    assert [r.task_id for r in results] == [other_id]


async def test_search_exclude_task_id_respects_k_starvation_at_low_search_effort(index, store):
    """The earlier "excluding 1 id can never starve k" reasoning only held
    for the default search_effort=400 -- it's a runtime setting, and can
    go as low as k itself. With search_effort==k, excluding the self row
    must still not come back short."""
    settings = index._settings.model_copy(update={"search_effort": 1})
    low_effort_index = PgVectorStore(settings)
    try:
        query_vec = embed("Ship the release notes for v2.3")
        self_id, other_id = f"test-{uuid.uuid4()}", f"test-{uuid.uuid4()}"
        await store.upsert(self_id, query_vec)
        await store.upsert(other_id, embed("Write up the v2.3 release notes"))

        results = await low_effort_index.for_tenant(TENANT).search(
            query_vec, k=1, exclude_task_id=self_id
        )

        assert [r.task_id for r in results] == [other_id]
    finally:
        await low_effort_index.close()


async def test_search_combines_exclude_completed_and_exclude_task_id(store):
    """Both filters at once -- not exercised by find_related()/find_duplicate()
    today, but part of search()'s public contract."""
    query_vec = embed("Ship the release notes for v2.3")
    self_id, done_id, open_id = (f"test-{uuid.uuid4()}" for _ in range(3))
    await store.upsert(self_id, query_vec)
    await store.upsert(done_id, embed("Write up the v2.3 release notes"))
    await store.mark_completed(done_id)
    await store.upsert(open_id, embed("v2.3 release notes draft"))

    results = await store.search(query_vec, k=5, exclude_completed=True, exclude_task_id=self_id)

    assert [r.task_id for r in results] == [open_id]


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


async def test_search_never_returns_another_tenants_rows(store, other_store):
    vec = embed("Ship the release notes for v2.3")
    await store.upsert("task-mine", vec)
    await other_store.upsert("task-theirs", vec)

    results = await store.search(vec, k=5)

    assert [r.task_id for r in results] == ["task-mine"]


async def test_reads_and_writes_by_task_id_stay_inside_the_tenant(store, other_store):
    vec = embed("Ship the release notes for v2.3")
    await other_store.upsert("task-theirs", vec, content_hash="abc123")

    assert await store.get_vector("task-theirs") is None
    assert await store.get_content_hash("task-theirs") is None
    assert await store.mark_completed("task-theirs") is False
    await store.delete("task-theirs")
    assert await other_store.get_vector("task-theirs") is not None


async def test_upsert_does_not_take_over_another_tenants_row(store, other_store):
    vec = embed("Ship the release notes for v2.3")
    await other_store.upsert("task-1", vec)

    await store.upsert("task-1", embed("Something else entirely"))

    assert await other_store.get_vector("task-1") is not None
    assert await store.get_vector("task-1") is None


async def test_tenant_of_returns_the_tenant_a_task_is_indexed_under(index, store):
    await store.upsert("task-1", embed("Ship the release notes"))

    assert await index.tenant_of("task-1") == TENANT
    assert await index.tenant_of("task-unknown") is None


async def test_a_row_without_a_tenant_is_never_returned(index, store):
    vec = embed("Ship the release notes for v2.3")
    await _insert_without_tenant(index, "task-old", vec)

    assert await store.search(vec, k=5) == []
    assert await store.get_vector("task-old") is None
    assert await index.tenant_of("task-old") is None


async def test_upsert_gives_a_row_without_a_tenant_its_tenant(index, store):
    vec = embed("Ship the release notes for v2.3")
    await _insert_without_tenant(index, "task-old", vec)

    await store.upsert("task-old", vec, content_hash="abc123")

    assert await index.tenant_of("task-old") == TENANT
    assert [r.task_id for r in await store.search(vec, k=5)] == ["task-old"]


async def test_search_raises_when_database_is_unreachable():
    """An unreachable DB surfaces as an error, not a hang."""
    unreachable_settings = VectorStoreSettings(
        dsn="postgresql://doesnotexist:doesnotexist@127.0.0.1:1/doesnotexist?connect_timeout=2",
        table="task_embeddings_test",
    )
    unreachable_index = PgVectorStore(unreachable_settings, pool_timeout=5)
    try:
        with pytest.raises(PoolTimeout):
            await unreachable_index.for_tenant(TENANT).search([0.0] * EMBEDDING_DIM, k=1)
    finally:
        await unreachable_index.close()  # the pool opened before timing out


async def test_open_raises_a_clear_error_when_the_table_is_not_migrated():
    """The most important new behavior here: an unmigrated DB fails with
    a clear message, not a bare UndefinedTable from the first real query."""
    missing_settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table=f"missing_{uuid.uuid4().hex}",
    )
    missing_store = PgVectorStore(missing_settings)
    try:
        with pytest.raises(RuntimeError, match="schema not migrated"):
            await missing_store.open()
    finally:
        await missing_store.close()


@asynccontextmanager
async def _fake_async_ctx(value):
    yield value


def _mocked_index(mock_cursor):
    index = PgVectorStore.__new__(PgVectorStore)  # skip __init__: no real pool needed
    index._settings = VectorStoreSettings(
        dsn="postgresql://u:p@h:5432/d", table="task_embeddings_test", search_effort=400
    )
    index._ready = True  # skip _ensure_ready(): no real connection needed

    mock_conn = MagicMock()
    mock_conn.cursor = MagicMock(side_effect=lambda: _fake_async_ctx(mock_cursor))

    index._pool = MagicMock()
    index._pool.connection = MagicMock(side_effect=lambda: _fake_async_ctx(mock_conn))
    return index


async def test_search_issues_set_local_ef_search_before_the_select():
    """Mocked cursor: search() sets hnsw.ef_search before the data query."""
    mock_cursor = AsyncMock()
    mock_cursor.fetchall.return_value = [("task-1", 0.9, False)]
    index = _mocked_index(mock_cursor)

    results = await index.for_tenant(TENANT).search([0.1, 0.2], k=5)

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
    assert str(index._settings.search_effort) in ef_search_params
    assert data_query_calls, "search() never issued the actual data SELECT"
    assert ef_search_calls[0] < data_query_calls[0], "ef_search must be set before the data query"


async def test_search_always_turns_on_iterative_scan():
    """The tenant condition alone can leave k short without it (#841)."""
    mock_cursor = AsyncMock()
    mock_cursor.fetchall.return_value = []
    index = _mocked_index(mock_cursor)

    await index.for_tenant(TENANT).search([0.1, 0.2], k=5)

    executed_sql = [str(call.args[0]) for call in mock_cursor.execute.call_args_list]
    assert any("hnsw.iterative_scan" in sql for sql in executed_sql)


async def test_search_query_can_use_the_hnsw_index(index, store):
    """ORDER BY must use the raw distance expression, or Postgres falls
    back to a full table scan instead of the HNSW index."""
    async with index._pool.connection() as conn, conn.cursor() as cur:
        await cur.execute("SET LOCAL enable_seqscan = off;")
        # Else the tenant_id btree plus a sort wins on a near-empty table.
        await cur.execute("SET LOCAL enable_sort = off;")
        await cur.execute(
            sql.SQL("EXPLAIN {}").format(
                store._search_query(exclude_completed=False, exclude_task_id=False)
            ),
            ([0.0] * EMBEDDING_DIM, TENANT, [0.0] * EMBEDDING_DIM, 1),
        )
        plan = "\n".join(row[0] for row in await cur.fetchall())
    assert "embedding_hnsw_idx" in plan, f"query plan did not use the HNSW index:\n{plan}"


async def test_search_query_can_use_the_hnsw_index_with_exclude_completed(index, store):
    """WHERE NOT is_completed must not force a fall back to a full table
    scan either -- that's the whole point of filtering in the query."""
    async with index._pool.connection() as conn, conn.cursor() as cur:
        await cur.execute("SET LOCAL enable_seqscan = off;")
        # Else the tenant_id btree plus a sort wins on a near-empty table.
        await cur.execute("SET LOCAL enable_sort = off;")
        await cur.execute(
            sql.SQL("EXPLAIN {}").format(
                store._search_query(exclude_completed=True, exclude_task_id=False)
            ),
            ([0.0] * EMBEDDING_DIM, TENANT, [0.0] * EMBEDDING_DIM, 1),
        )
        plan = "\n".join(row[0] for row in await cur.fetchall())
    assert "embedding_hnsw_idx" in plan, f"query plan did not use the HNSW index:\n{plan}"


async def test_search_query_can_use_the_hnsw_index_with_exclude_task_id(index, store):
    """Same guarantee for #472's self-exclusion filter."""
    async with index._pool.connection() as conn, conn.cursor() as cur:
        await cur.execute("SET LOCAL enable_seqscan = off;")
        # Else the tenant_id btree plus a sort wins on a near-empty table.
        await cur.execute("SET LOCAL enable_sort = off;")
        await cur.execute(
            sql.SQL("EXPLAIN {}").format(
                store._search_query(exclude_completed=False, exclude_task_id=True)
            ),
            ([0.0] * EMBEDDING_DIM, TENANT, "some-task-id", [0.0] * EMBEDDING_DIM, 1),
        )
        plan = "\n".join(row[0] for row in await cur.fetchall())
    assert "embedding_hnsw_idx" in plan, f"query plan did not use the HNSW index:\n{plan}"
