"""
Vector-search latency + recall benchmark for the production embeddings
module (`src/mind/embeddings`) -- the real BGE model, the real pgvector
store, at a realistic load scale.

Addresses issue #87:
  1. 100 similarity queries -> p50/p95/p99 search() latency.
  2. Recall: 5-10 manually selected near-duplicate pairs -- does searching
     one member return the other in the top-k?
  3. Synthetic filler vectors (5,000-10,000) so latency is measured at a
     realistic scale, not on a handful of rows. Every filler row's id is
     prefixed `filler-` so it can never be mistaken for real data.
  4. Results get written into `vector-store-comparison.md`'s "Benchmark
     results" section (not a new file) -- this script itself just
     produces the numbers (printed + a local, gitignored JSON for
     reference).

Run:  python vector_search_benchmark.py
(from teams/mind/, with MIND_VECTOR_STORE__DSN pointed at a real pgvector
instance and the `mind` package installed -- same setup as the test
suite.)
"""

import asyncio
import json
import random
import statistics
import time

from mind.config import get_settings
from mind.embeddings import embed, embed_batch
from mind.embeddings.pgvector_store import PgVectorStore

N_FILLER = 10_000  # upper end of the 5,000-10,000 range asked for
N_LATENCY_QUERIES = 100
K_VALUES = (5, 10)

FILLER_WORDS = [
    "fix", "login", "bug", "invoice", "customer", "database", "report",
    "sync", "github", "issue", "crm", "ticket", "refund", "deploy",
    "payment", "duplicate", "task", "review", "merge", "server",
    "timeout", "cache", "queue", "notification", "webhook", "api",
]

# 8 manually-selected near-duplicate pairs: same underlying problem,
# different wording -- the recall test.
PAIRS: list[tuple[str, str]] = [
    (
        "Fix the login bug on the payments page",
        "Payments page login button is broken, please repair it",
    ),
    (
        "Customer reported duplicate invoices being generated",
        "Client says the billing system is creating the same invoice twice",
    ),
    (
        "Server crashed in production overnight",
        "Production server went down unexpectedly last night",
    ),
    (
        "Database deployment rollback needed",
        "Need to revert the recent database migration deployment",
    ),
    (
        "Employee onboarding paperwork for new hire",
        "New team member needs onboarding documents prepared",
    ),
    (
        "Customer complains about billing overcharge",
        "Client says they were charged too much on their last invoice",
    ),
    (
        "Kubernetes pod stuck restarting",
        "K8s pod keeps crash-looping and won't stay up",
    ),
    (
        "Annual leave request approval",
        "Need to approve a team member's vacation time-off request",
    ),
]
assert 5 <= len(PAIRS) <= 10


def fake_filler_text() -> str:
    return " ".join(random.choices(FILLER_WORDS, k=random.randint(4, 10)))


def percentile(values: list[float], p: float) -> float:
    values = sorted(values)
    idx = round((p / 100) * (len(values) - 1))
    return values[idx]


async def main() -> None:
    random.seed(87)
    store = PgVectorStore(get_settings().vector_store)
    await store.open()
    print("Resetting table...")
    await store.clear()

    # --- 1. Load synthetic filler (clearly id-prefixed, batch-embedded) ---
    print(f"Generating {N_FILLER} synthetic filler texts...")
    filler_texts = [fake_filler_text() for _ in range(N_FILLER)]
    filler_ids = [f"filler-{i:06d}" for i in range(N_FILLER)]

    print("Embedding filler in batches (this is the slow part)...")
    BATCH = 500
    filler_vectors: list[list[float]] = []
    for start in range(0, N_FILLER, BATCH):
        chunk = filler_texts[start : start + BATCH]
        filler_vectors.extend(embed_batch(chunk))
        if (start // BATCH) % 4 == 0:
            print(f"  embedded {start + len(chunk)}/{N_FILLER}")

    # Upserted concurrently in batches, not one `await` per row -- the
    # pool has up to 5 connections (see PgVectorStore's max_conn) that a
    # fully sequential loop never uses more than one of at a time. Batched
    # (rather than one giant gather() of all 10,000) to keep progress
    # visible and the number of in-flight coroutines bounded.
    print("Upserting filler rows...")
    for start in range(0, N_FILLER, BATCH):
        batch_ids = filler_ids[start : start + BATCH]
        batch_vectors = filler_vectors[start : start + BATCH]
        await asyncio.gather(
            *(store.upsert(task_id, vector) for task_id, vector in zip(batch_ids, batch_vectors))
        )
        if (start // BATCH) % 4 == 0:
            print(f"  upserted {start + len(batch_ids)}/{N_FILLER}")

    # --- 2. Load the manually-selected pairs (passage side, is_query=False) ---
    print(f"Loading {len(PAIRS)} manually-selected near-duplicate pairs...")
    pair_rows = []  # (id_a, id_b, text_a, text_b)
    pair_upserts = []  # (task_id, vector)
    for i, (text_a, text_b) in enumerate(PAIRS):
        id_a, id_b = f"pair-{i}-a", f"pair-{i}-b"
        pair_upserts.append((id_a, embed(text_a)))
        pair_upserts.append((id_b, embed(text_b)))
        pair_rows.append((id_a, id_b, text_a, text_b))
    await asyncio.gather(*(store.upsert(task_id, vector) for task_id, vector in pair_upserts))

    total_rows = N_FILLER + 2 * len(PAIRS)
    print(f"Total rows loaded: {total_rows}")

    # --- 3. Latency: 100 similarity queries, p50/p95/p99 of search() alone ---
    print(f"Measuring search() latency over {N_LATENCY_QUERIES} queries...")
    query_latencies = []
    for _ in range(N_LATENCY_QUERIES):
        qvec = embed(fake_filler_text(), is_query=True)
        start = time.perf_counter()
        await store.search(qvec, k=10)
        query_latencies.append((time.perf_counter() - start) * 1000)

    # --- 4. Recall: does querying one pair member surface the other? ---
    print("Measuring duplicate-pair recall...")
    pair_details = []
    pair_hits = {k: [] for k in K_VALUES}
    for id_a, id_b, text_a, text_b in pair_rows:
        query_vec = embed(text_a, is_query=True)  # BGE asymmetry: query side
        hits_this_pair = {}
        for k in K_VALUES:
            results = await store.search(query_vec, k=k)
            hit = id_b in [r.task_id for r in results]
            pair_hits[k].append(hit)
            hits_this_pair[k] = hit
        pair_details.append({
            "text_a": text_a, "text_b": text_b,
            "id_a": id_a, "id_b": id_b,
            **{f"hit_at_{k}": v for k, v in hits_this_pair.items()},
        })
    recall_summary = {f"recall_at_{k}": sum(hits) / len(hits) for k, hits in pair_hits.items()}

    results = {
        "dataset_composition": {
            "synthetic_filler": N_FILLER,
            "manual_pair_rows": 2 * len(PAIRS),
            "total_rows": total_rows,
        },
        "search_latency_ms": {
            "n_queries": N_LATENCY_QUERIES,
            "avg": statistics.mean(query_latencies),
            "p50": percentile(query_latencies, 50),
            "p95": percentile(query_latencies, 95),
            "p99": percentile(query_latencies, 99),
            "max": max(query_latencies),
        },
        "p95_under_300ms": percentile(query_latencies, 95) < 300,
        "duplicate_pair_recall": {
            "summary": recall_summary,
            "details": pair_details,
        },
    }

    print("\n=== Summary ===")
    print(f"Total rows: {total_rows} ({N_FILLER} synthetic filler + {2 * len(PAIRS)} manual pairs)")
    print(f"search() latency: avg={results['search_latency_ms']['avg']:.2f}ms "
          f"p50={results['search_latency_ms']['p50']:.2f}ms "
          f"p95={results['search_latency_ms']['p95']:.2f}ms "
          f"p99={results['search_latency_ms']['p99']:.2f}ms "
          f"max={results['search_latency_ms']['max']:.2f}ms")
    print(f"<300ms p95 target: {'PASS' if results['p95_under_300ms'] else 'FAIL'}")
    print(f"Duplicate-pair recall: {recall_summary}")
    for d in pair_details:
        status = "HIT " if d["hit_at_10"] else "MISS"
        print(f"  [{status}] \"{d['text_a']}\" -> \"{d['text_b']}\"")

    def _write_results() -> None:
        with open("vector_search_benchmark_results.json", "w") as f:
            json.dump(results, f, indent=2)

    # Blocking file I/O -- offload to a thread so this async function
    # never blocks the event loop, even for this one quick write.
    await asyncio.to_thread(_write_results)
    print("\nWrote vector_search_benchmark_results.json (local reference, gitignored)")

    await store.close()


if __name__ == "__main__":
    asyncio.run(main())
