"""Smoke test for vector_search_benchmark.py's real-DB path -- not the
real benchmark, just confirms the table gets created and a basic
upsert/search round-trip works (make lint never runs benchmarks/, only
type-checks it, so this gap went undetected until a manual run)."""

import os

from benchmarks.vector_search_benchmark import BENCHMARK_TABLE, _create_benchmark_table

from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.pgvector_store import PgVectorStore


async def test_benchmark_table_gets_created_and_is_usable():
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table=BENCHMARK_TABLE,
    )
    await _create_benchmark_table(settings)

    store = PgVectorStore(settings)
    try:
        await store.open()  # raises RuntimeError before _create_benchmark_table()
        await store.clear()
        await store.upsert("smoke-task", embed("Smoke test task"))
        results = await store.search(embed("Smoke test task"), k=1)
        assert results[0].task_id == "smoke-task"
    finally:
        await store.clear()
        await store.close()
