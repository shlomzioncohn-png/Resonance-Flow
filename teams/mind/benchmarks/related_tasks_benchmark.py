"""#472: find_related() p50/p95 -- at the 21-task pool (current real
order of magnitude) and at 10,000+ synthetic filler rows (not a claim
about current volume, just a scale check). See embeddings/README.md.

Run: python -m benchmarks.related_tasks_benchmark
"""

import asyncio
import json
import os
import random
import statistics
import time
from pathlib import Path

from mind.config import VectorStoreSettings
from mind.embeddings import embed, embed_batch, find_related, task_text
from mind.embeddings.pgvector_store import PgVectorStore

_TASKS_PATH = Path(__file__).resolve().parent / "threshold_calibration_tasks.json"
_RESULTS_PATH = Path(__file__).resolve().parent / "related_tasks_benchmark_results.json"
_TABLE = "task_embeddings_related_benchmark"

N_FILLER = 10_000
N_LATENCY_QUERIES = 100

FILLER_WORDS = [
    "fix",
    "login",
    "bug",
    "invoice",
    "customer",
    "database",
    "report",
    "sync",
    "github",
    "issue",
    "crm",
    "ticket",
    "refund",
    "deploy",
    "payment",
    "duplicate",
    "task",
    "review",
    "merge",
    "server",
    "timeout",
    "cache",
    "queue",
    "notification",
    "webhook",
    "api",
]


def _fake_filler_text() -> str:
    return " ".join(random.choices(FILLER_WORDS, k=random.randint(4, 10)))


def _percentile(values: list[float], p: float) -> float:
    values = sorted(values)
    idx = round((p / 100) * (len(values) - 1))
    return values[idx]


async def _measure(store: PgVectorStore, task_ids: list[str], n_queries: int) -> dict[str, float]:
    latencies = []
    for _ in range(n_queries):
        task_id = random.choice(task_ids)
        t0 = time.perf_counter()
        await find_related(store, task_id)
        latencies.append((time.perf_counter() - t0) * 1000)
    return {
        "n_queries": n_queries,
        "avg": statistics.mean(latencies),
        "p50": _percentile(latencies, 50),
        "p95": _percentile(latencies, 95),
        "max": max(latencies),
    }


async def main() -> None:
    random.seed(472)
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],  # type: ignore[arg-type]
        table=_TABLE,
    )
    from benchmarks.vector_store_test_table import create_vector_store_test_table

    await create_vector_store_test_table(settings)
    store = PgVectorStore(settings)
    await store.open()
    try:
        await store.clear()
        real_tasks = json.loads(_TASKS_PATH.read_text(encoding="utf-8"))["tasks"]
        real_task_ids = [t["task_id"] for t in real_tasks]
        for t in real_tasks:
            await store.upsert(t["task_id"], embed(task_text(t["title"], t["description"])))

        print(f"--- At the current real order of magnitude: {len(real_task_ids)} rows ---")
        current_scale = await _measure(store, real_task_ids, N_LATENCY_QUERIES)
        print(
            f"avg={current_scale['avg']:.2f}ms p50={current_scale['p50']:.2f}ms "
            f"p95={current_scale['p95']:.2f}ms max={current_scale['max']:.2f}ms"
        )

        print(f"\nLoading {N_FILLER} synthetic filler rows for the at-scale check...")
        filler_ids = [f"filler-{i:06d}" for i in range(N_FILLER)]
        filler_texts = [_fake_filler_text() for _ in range(N_FILLER)]
        BATCH = 500
        for start in range(0, N_FILLER, BATCH):
            chunk_ids = filler_ids[start : start + BATCH]
            chunk_vectors = embed_batch(filler_texts[start : start + BATCH])
            await asyncio.gather(
                *(store.upsert(tid, v) for tid, v in zip(chunk_ids, chunk_vectors))
            )
            if (start // BATCH) % 4 == 0:
                print(f"  upserted {start + len(chunk_ids)}/{N_FILLER}")

        print("\n--- At 10,000+ rows (synthetic filler, not current production volume) ---")
        at_scale = await _measure(store, real_task_ids, N_LATENCY_QUERIES)
        print(
            f"avg={at_scale['avg']:.2f}ms p50={at_scale['p50']:.2f}ms "
            f"p95={at_scale['p95']:.2f}ms max={at_scale['max']:.2f}ms"
        )
    finally:
        await store.clear()
        await store.close()

    results = {
        "current_real_order_of_magnitude": {"row_count": len(real_task_ids), **current_scale},
        "synthetic_10k_scale": {"row_count": N_FILLER + len(real_task_ids), **at_scale},
        "p95_under_300ms_at_scale": at_scale["p95"] < 300,
    }
    print(
        f"\n<300ms p95 at 10k+ scale: {'PASS' if results['p95_under_300ms_at_scale'] else 'FAIL'}"
    )

    def _write_results() -> None:
        _RESULTS_PATH.write_text(json.dumps(results, indent=2))

    await asyncio.to_thread(_write_results)
    print(f"\nWrote {_RESULTS_PATH.name} (local reference, gitignored)")


if __name__ == "__main__":
    # psycopg async rejects Windows' default ProactorEventLoop.
    asyncio.run(main(), loop_factory=asyncio.SelectorEventLoop)
