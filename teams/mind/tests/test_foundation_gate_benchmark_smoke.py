"""Smoke test for benchmarks/foundation_gate_benchmark.py's real-DB path.

Not the real benchmark (no 100+ vectors, no real_slice data) -- just
confirms the dedicated bench table actually gets created and a basic
upsert/search round-trip works. make test runs tests/, but never the
benchmark scripts themselves (make lint only type/syntax-checks them),
so "nobody creates the table anymore" (PgVectorStore stopped
self-healing its schema, issue #441) went undetected by CI until a
manual run. This closes that gap for this benchmark specifically.
"""

import os

from benchmarks.foundation_gate_benchmark import _BENCH_TABLE, _create_bench_table

from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.pgvector_store import PgVectorStore


async def test_bench_table_gets_created_and_is_usable():
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table=_BENCH_TABLE,
    )
    await _create_bench_table(settings)

    store = PgVectorStore(settings)
    try:
        await store.open()  # raises RuntimeError before _create_bench_table()
        await store.clear()
        await store.upsert("smoke-task", embed("Smoke test task"))
        results = await store.search(embed("Smoke test task"), k=1)
        assert results[0].task_id == "smoke-task"
    finally:
        await store.clear()
        await store.close()
