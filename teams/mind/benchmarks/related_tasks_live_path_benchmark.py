"""#475: prove the <300ms p95 latency target through the actual live
search path, under realistic conditions -- not a hand-written query.

`vector_search_benchmark.py` (#87) measures `PgTenantStore.search()`
alone. That skips two real round trips the live endpoint
(`GET /v1/tasks/{task_id}/related`, #472) makes: `VectorIndex.tenant_of()`
and `VectorStore.get_vector()`. This benchmark calls
`mind.api.v1.router.get_related_tasks()` itself -- the literal function
FastAPI invokes for that route -- so it exercises `instrumented_call`,
`tenant_of()`, `find_related()` (`get_vector()` + `search()`), and
threshold filtering, in the same order the live path does.

Not driven through a running HTTP server or ASGI transport: the app's
own lifespan (`api/main.py`) also starts Kafka consumers and the score
publisher, neither of which bears on vector-search latency, and both of
which would make this benchmark require a Kafka broker just to start.
Calling the router function directly runs the exact same code; the one
thing it skips is the loopback socket hop into uvicorn, whose overhead
is itself sub-millisecond and not what #475 asks to prove.

Measures, as #475 requires:
  - p50/p95/p99 over a *varied* set of query task_ids (not one repeated).
  - Warm (a throwaway pass first), concurrent (many requests in flight
    at once, via a bounded semaphore), and with writes happening
    concurrently (a background task continuously upserting new rows,
    like the real index consumer does).
  - An `EXPLAIN ANALYZE` of the literal query `search()` runs (not a
    hand-written approximation of it), to confirm the planner actually
    uses the HNSW index at this scale rather than a sequential scan -- an
    explicit open question from `vector-store-comparison.md`
    ("Follow-up", #270) that this closes.

Run (from teams/mind/, with vector-store up and migrated):
    python -m benchmarks.related_tasks_live_path_benchmark [--vectors N]

Uses its own table (task_embeddings_benchmark_475), dropped after the
run -- never touches the real task_embeddings table.

**0 real vectors, 100% synthetic.** No real Mind task corpus exists at
this scale to load. Row text comes from `synthetic_task_seeds.py`:
realistic task descriptions an LLM wrote (not random word-soup -- see
that module's docstring for why gibberish or near-identical strings
would make the index's own behavior here unrepresentative), expanded
past the row count by re-texturing names/dates/numbers on each repeat
so reusing the seed corpus several times over doesn't itself produce
near-duplicate rows.
"""

import argparse
import asyncio
import json
import logging
import random
import statistics
import time
from pathlib import Path

import psycopg
from psycopg import sql

from benchmarks.synthetic_task_seeds import expand_to
from benchmarks.vector_store_test_table import create_vector_store_test_table
from mind.api.v1.router import get_related_tasks
from mind.config import VectorStoreSettings, get_settings
from mind.embeddings import embed_batch, task_text
from mind.embeddings.base import DEMO_TENANT_ID
from mind.embeddings.pgvector_store import PgTenantStore, PgVectorStore

BENCHMARK_TABLE = "task_embeddings_benchmark_475"
RESULTS_PATH = Path(__file__).resolve().parent / "related_tasks_live_path_benchmark_results.json"

N_MEASURED_QUERIES = 300
N_WARMUP_QUERIES = 20
N_CONCURRENT = 20  # in-flight get_related_tasks() calls at once
# Pool must cover every concurrent caller's in-flight connection, plus the
# background writer, each of which only holds one for the span of a single
# statement (PgTenantStore scopes a connection per call, not per request).
POOL_SIZE = N_CONCURRENT + 5
WRITER_BATCH = 200  # distinct rows the background writer cycles through
# Different from expand_to()'s own default (475): the writer's rows must
# not be byte-identical to the main dataset's first WRITER_BATCH rows.
_WRITER_SEED = 475_000


def percentile(values: list[float], p: float) -> float:
    values = sorted(values)
    idx = round((p / 100) * (len(values) - 1))
    return values[idx]


async def _load_rows(store: PgVectorStore, tenant_store, n: int) -> list[str]:
    """Load n rows of varied, realistic-looking synthetic task text (see
    synthetic_task_seeds.py), return their ids."""
    texts = [task_text(row["title"], row["description"]) for row in expand_to(n)]
    ids = [f"bench475-{i:07d}" for i in range(n)]
    print(f"Embedding {n} rows...")
    BATCH = 500
    vectors: list[list[float]] = []
    for start in range(0, n, BATCH):
        vectors.extend(embed_batch(texts[start : start + BATCH]))
        if (start // BATCH) % 4 == 0:
            print(f"  embedded {start + min(BATCH, n - start)}/{n}")
    print("Upserting...")
    for start in range(0, n, BATCH):
        batch_ids, batch_vecs = ids[start : start + BATCH], vectors[start : start + BATCH]
        await asyncio.gather(*(tenant_store.upsert(i, v) for i, v in zip(batch_ids, batch_vecs)))
        if (start // BATCH) % 4 == 0:
            print(f"  upserted {start + len(batch_ids)}/{n}")
    return ids


async def _background_writer(tenant_store, stop: asyncio.Event) -> int:
    """Continuously upsert rows, like the real index consumer -- runs for
    the whole measured query phase so latency is measured with writes in
    flight, not just reads against a frozen table."""
    texts = [
        task_text(row["title"], row["description"])
        for row in expand_to(WRITER_BATCH, seed=_WRITER_SEED)
    ]
    vectors = embed_batch(texts)
    writes = 0
    i = 0
    while not stop.is_set():
        task_id = f"bench475-writer-{i % WRITER_BATCH:04d}"
        await tenant_store.upsert(task_id, vectors[i % WRITER_BATCH])
        writes += 1
        i += 1
        await asyncio.sleep(0)  # yield, don't spin the loop solo
    return writes


async def _measured_query(index: PgVectorStore, task_id: str, sem: asyncio.Semaphore) -> float:
    async with sem:
        start = time.perf_counter()
        await get_related_tasks(task_id, index)
        return (time.perf_counter() - start) * 1000


async def _explain_hnsw_is_used(
    settings: VectorStoreSettings,
    tenant_store: PgTenantStore,
    sample_task_id: str,
    sample_vector: list[float],
) -> str:
    """#270, fixed per #475 review: an earlier version of this check hand-wrote
    its own query (a fake constant vector, no `exclude_task_id`, and -- the
    one that actually matters -- no `hnsw.iterative_scan`), which is not the
    query `search()` runs and silently passed even when the real path took a
    seq scan. Replays `PgTenantStore._search_query()` itself, with the same
    `set_config` calls `search()` makes, a real stored vector, and a real
    excluded task_id -- the exact statement `find_related()` sends."""
    query = tenant_store._search_query(exclude_completed=False, exclude_task_id=True)
    async with (
        await psycopg.AsyncConnection.connect(str(settings.dsn.get_secret_value())) as conn,
        conn.cursor() as cur,
    ):
        await cur.execute(
            "SELECT set_config('hnsw.ef_search', %s, true);", (str(settings.search_effort),)
        )
        await cur.execute("SELECT set_config('hnsw.iterative_scan', 'strict_order', true);")
        await cur.execute(
            sql.SQL("EXPLAIN (ANALYZE, FORMAT TEXT) ") + query,
            (sample_vector, tenant_store._tenant_id, sample_task_id, sample_vector, 5),
        )
        rows = await cur.fetchall()
        return "\n".join(r[0] for r in rows)


async def _run(n_vectors: int, ef_search: int) -> dict:
    # Instrumentation logs one JSON line per call; silence it here so
    # hundreds of concurrent benchmark calls don't flood stdout.
    logging.getLogger("mind.instrumentation").setLevel(logging.WARNING)

    settings = get_settings().vector_store.model_copy(
        update={"table": BENCHMARK_TABLE, "search_effort": ef_search}
    )
    await create_vector_store_test_table(settings)
    index = PgVectorStore(settings, max_conn=POOL_SIZE)
    await index.open()
    tenant_store = index.for_tenant(DEMO_TENANT_ID)
    try:
        ids = await _load_rows(index, tenant_store, n_vectors)

        print("Confirming the HNSW index is actually used (#270)...")
        sample_vector = await tenant_store.get_vector(ids[0])
        assert sample_vector is not None
        plan = await _explain_hnsw_is_used(settings, tenant_store, ids[0], sample_vector)
        print(plan)
        uses_hnsw = "Index Scan" in plan and "hnsw" in plan.lower()

        print(f"Warming up ({N_WARMUP_QUERIES} queries, not measured)...")
        warmup_sem = asyncio.Semaphore(N_CONCURRENT)
        await asyncio.gather(
            *(
                _measured_query(index, random.choice(ids), warmup_sem)
                for _ in range(N_WARMUP_QUERIES)
            )
        )

        print(
            f"Measuring {N_MEASURED_QUERIES} live-path queries "
            f"({N_CONCURRENT} concurrent, writes in flight)..."
        )
        stop_writer = asyncio.Event()
        writer_task = asyncio.create_task(_background_writer(tenant_store, stop_writer))
        sem = asyncio.Semaphore(N_CONCURRENT)
        query_ids = [random.choice(ids) for _ in range(N_MEASURED_QUERIES)]
        latencies = await asyncio.gather(
            *(_measured_query(index, tid, sem) for tid in query_ids)
        )
        stop_writer.set()
        writes_during_measurement = await writer_task

        stats = {
            "n_vectors": n_vectors,
            "ef_search": ef_search,
            "n_queries": N_MEASURED_QUERIES,
            "n_concurrent": N_CONCURRENT,
            "writes_during_measurement": writes_during_measurement,
            "avg_ms": statistics.mean(latencies),
            "p50_ms": percentile(latencies, 50),
            "p95_ms": percentile(latencies, 95),
            "p99_ms": percentile(latencies, 99),
            "max_ms": max(latencies),
            "p95_under_300ms": percentile(latencies, 95) < 300,
            "hnsw_index_used": uses_hnsw,
            "explain_plan": plan,
        }
        print("\n=== Summary ===")
        print(f"n_vectors={n_vectors}  writes_during_measurement={writes_during_measurement}")
        print(
            f"avg={stats['avg_ms']:.2f}ms p50={stats['p50_ms']:.2f}ms "
            f"p95={stats['p95_ms']:.2f}ms p99={stats['p99_ms']:.2f}ms max={stats['max_ms']:.2f}ms"
        )
        print(f"<300ms p95 target: {'PASS' if stats['p95_under_300ms'] else 'FAIL'}")
        print(f"HNSW index used (not seq scan): {uses_hnsw}")
        return stats
    finally:
        await index.clear()
        await index.close()


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", type=int, default=10_000)
    # Mirrors VectorStoreSettings.search_effort's production default (#475
    # review): 400 made the planner cost an HNSW scan above Seq Scan + sort
    # at 10,000 rows, so the index went unused -- see
    # vector-store-comparison.md's "Live-path latency" section for why the
    # default moved to 200.
    parser.add_argument("--ef-search", type=int, default=200)
    args = parser.parse_args()

    random.seed(475)
    stats = await _run(args.vectors, args.ef_search)

    def _write() -> None:
        RESULTS_PATH.write_text(json.dumps(stats, indent=2))

    await asyncio.to_thread(_write)
    print(f"\nWrote {RESULTS_PATH.name} (local reference, gitignored)")


if __name__ == "__main__":
    # psycopg async rejects Windows' default ProactorEventLoop.
    asyncio.run(main(), loop_factory=asyncio.SelectorEventLoop)
