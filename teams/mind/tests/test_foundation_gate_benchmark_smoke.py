"""Smoke test for foundation_gate_benchmark.py's real-DB path -- not the
real benchmark, just confirms the bench table gets created and a basic
upsert/search round-trip works (make lint never runs benchmarks/, only
type-checks it, so this gap went undetected until a manual run). Calls
_open_bench_store() itself, the same helper main() calls, rather than
duplicating its setup -- so a regression in main()'s own setup (e.g. the
#441 schema-creation call) actually fails this test too."""

import os

from benchmarks.foundation_gate_benchmark import _BENCH_TABLE, _open_bench_store

from mind.config import VectorStoreSettings
from mind.embeddings import DEMO_TENANT_ID, embed


async def test_bench_table_gets_created_and_is_usable():
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table=_BENCH_TABLE,
    )
    index = await _open_bench_store(settings)  # raises RuntimeError if main()'s setup regresses
    store = index.for_tenant(DEMO_TENANT_ID)
    try:
        await index.clear()
        await store.upsert("smoke-task", embed("Smoke test task"))
        results = await store.search(embed("Smoke test task"), k=1)
        assert results[0].task_id == "smoke-task"
    finally:
        await index.clear()
        await index.close()
