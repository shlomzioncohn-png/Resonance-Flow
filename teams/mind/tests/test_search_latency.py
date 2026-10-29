"""#475/#1033 review: a real-DB regression test that the production path
actually uses the HNSW index at Settings' real default `search_effort`, on
a table large enough for the planner's cost model to have a genuine choice
-- unlike test_vector_store.py's own EXPLAIN tests, which force
`enable_seqscan = off` on a near-empty table to prove the index merely
*can* be used, not that it naturally *is*.

Random vectors, not real embeddings: whether the planner picks the HNSW
index here turns on estimated row/candidate counts and `ef_search`, not
on vector content, and generating them inside Postgres itself (one SQL
statement, no model, no per-row round trip) keeps this fast enough to
run on every PR.
"""

import os

import pytest
from benchmarks.vector_store_test_table import create_vector_store_test_table
from psycopg import AsyncConnection, sql

from mind.config import VectorStoreSettings
from mind.embeddings.embeddings import EMBEDDING_DIM
from mind.embeddings.pgvector_store import PgVectorStore
from mind.embeddings.related_tasks import DEFAULT_K

TABLE = "task_embeddings_test_latency"
TENANT = "5c6a4b3e-2d1f-4a0e-8c9b-7f6e5d4c3b2a"
N_ROWS = 10_000
INDEX_NAME = f"{TABLE}_embedding_hnsw_idx"


async def _load_random_rows(settings: VectorStoreSettings, n: int) -> None:
    table = sql.Identifier(settings.table)
    async with await AsyncConnection.connect(
        str(settings.dsn.get_secret_value()), autocommit=True
    ) as conn:
        await conn.execute(
            sql.SQL(
                """
                INSERT INTO {table} (task_id, tenant_id, embedding)
                SELECT
                    'latency-' || gs.i,
                    %(tenant)s,
                    (SELECT array_agg(random() * 2 - 1) FROM generate_series(1, %(dim)s))::vector
                FROM generate_series(1, %(n)s) AS gs(i);
                """
            ).format(table=table),
            {"tenant": TENANT, "dim": EMBEDDING_DIM, "n": n},
        )
        await conn.execute(sql.SQL("ANALYZE {table};").format(table=table))


@pytest.fixture(scope="module")
async def index():
    settings = VectorStoreSettings(dsn=os.environ["MIND_VECTOR_STORE__DSN"], table=TABLE)
    await create_vector_store_test_table(settings)
    s = PgVectorStore(settings)
    await s.open()
    await _load_random_rows(settings, N_ROWS)
    yield s
    await s.clear()
    await s.close()


async def _explain(index: PgVectorStore, ef_search: int) -> str:
    """Replays the exact query search() runs (same set_config calls,
    same _search_query()), the way #475 review required -- not a
    hand-written approximation that can silently diverge from it."""
    store = index.for_tenant(TENANT)
    sample_vector = [0.3] * EMBEDDING_DIM
    async with index._pool.connection() as conn, conn.cursor() as cur:
        await cur.execute("SELECT set_config('hnsw.ef_search', %s, true);", (str(ef_search),))
        await cur.execute("SELECT set_config('hnsw.iterative_scan', 'strict_order', true);")
        await cur.execute(
            sql.SQL("EXPLAIN (ANALYZE, FORMAT TEXT) ")
            + store._search_query(exclude_completed=False, exclude_task_id=False),
            (sample_vector, TENANT, sample_vector, DEFAULT_K),
        )
        rows = await cur.fetchall()
        return "\n".join(r[0] for r in rows)


async def test_hnsw_index_is_used_at_the_default_search_effort(index):
    """The regression #475 found: ef_search=400 (the old default) made the
    planner cost an HNSW scan above a Seq Scan at this row count, so the
    index went unused and the live-path p95 target failed. Settings' real
    default (not a value this test picks) must not regress to that."""
    plan = await _explain(index, VectorStoreSettings.model_fields["search_effort"].default)
    assert "Seq Scan" not in plan, f"planner fell back to a Seq Scan:\n{plan}"
    assert INDEX_NAME in plan, f"HNSW index was not used:\n{plan}"


async def test_a_high_enough_search_effort_does_fall_back_to_a_seq_scan(index):
    """Negative control: proves the assertion above can actually fail.
    #475's original EXPLAIN check (#1033 review) silently passed even
    when the real path took a Seq Scan -- a check that can't fail is
    worthless, so this confirms this one can."""
    plan = await _explain(index, 400)
    assert "Seq Scan" in plan, f"expected ef_search=400 to force a Seq Scan:\n{plan}"
