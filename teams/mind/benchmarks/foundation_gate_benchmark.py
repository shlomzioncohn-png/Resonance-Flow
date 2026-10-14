"""#469 (Foundation Gate, week 3): the vector-store benchmark that previous
runs couldn't do -- `eval/real_slice/`'s AMI-derived tasks are real,
human-annotated action items (not the synthetic/placeholder data earlier
PoC rounds were blocked to, see vector-store-comparison.md's "Validation"
section), and the vector-store interface is final.

Two separate things are measured, on purpose:
  - Latency (p50/p95/p99): needs scale, not realism -- the 16 real tasks
    are padded with synthetic filler to reach the >=100-vector minimum
    this doc's "Mandatory Requirements" section sets. Padding only ever
    affects scale, never the recall measurement below.
  - Recall: needs realism, not scale -- three real, hand-identified
    related-task pairs within the same AMI meeting (see
    _RELATED_PAIRS below), each with a control task from the same
    scenario that is NOT related. A real near-duplicate-pair corpus
    doesn't exist in this data (no two AMI action items are paraphrases
    of each other); "does a related real task rank above an unrelated
    real task from the same meeting" is the real-data question this
    slice can actually answer.

Writes results to foundation_gate_benchmark_results.json and prints a
summary -- see vector-store-comparison.md's "Foundation Gate benchmark
(week 3)" section for the numbers from the last real run.

Usage (from teams/mind/, vector-store already up -- see
src/mind/embeddings/README.md's "Foundation Gate benchmark" section):
    python -m benchmarks.foundation_gate_benchmark
"""

import asyncio
import json
import os
import random
import statistics
import time
from pathlib import Path
from typing import Any

from benchmarks.vector_store_test_table import create_vector_store_test_table
from mind.config import VectorStoreSettings
from mind.embeddings import embed
from mind.embeddings.pgvector_store import PgVectorStore

_ROOT = Path(__file__).resolve().parent.parent
_REAL_SLICE_DIR = _ROOT / "eval" / "real_slice"
_RESULTS_FILE = Path(__file__).resolve().parent / "foundation_gate_benchmark_results.json"
_BENCH_TABLE = "task_embeddings_foundation_gate_bench"

# Hand-identified from eval/real_slice/*.json (verbatim AMI action items,
# see each file's "source" field). Each pair genuinely discusses the same
# sub-topic within one meeting; each control is a real task from the SAME
# meeting that is not -- so a miss means the embedding model's recall
# problem (see vector-store-comparison.md), not a benchmark artifact like
# cross-meeting topic drift.
_RELATED_PAIRS: list[dict[str, str]] = [
    {
        # office_move: both about collecting seating preferences.
        "query_id": "task_team_preferences",
        "related_id": "task_poll_others",
        "control_id": "task_followup_meeting",  # a calendar event, unrelated
    },
    {
        # functional_design: components-concept is blocked on this same
        # voice-recognition research (both action items mention it).
        "query_id": "task_components_concept",
        "related_id": "task_research_vr_help",
        "control_id": "task_marketing_concept",  # unrelated prep item
    },
    {
        # budget_close: both are the Project Manager's reporting tasks to
        # management from the same action item pair.
        "query_id": "task_present_promo_plan",
        "related_id": "task_negotiate_cost",
        "control_id": "task_product_evaluation",  # unrelated, blocked on production
    },
]

_FILLER_WORDS = [
    "update", "review", "document", "pipeline", "ticket", "customer",
    "invoice", "schedule", "report", "meeting", "budget", "design",
    "server", "client", "deploy", "config", "metric", "backup",
    "release", "feature", "bug", "audit", "vendor", "contract", "survey",
]


def load_real_slice() -> list[tuple[str, str]]:
    """Returns [(task_id, "title description"), ...] for every task across
    every eval/real_slice/*.json scenario file."""
    tasks = []
    for path in sorted(_REAL_SLICE_DIR.glob("*.json")):
        scenario = json.loads(path.read_text())
        for task in scenario["tasks"]:
            text = f"{task['title']} {task['description']}".strip()
            tasks.append((task["task_id"], text))
    return tasks


def _synthetic_filler(count: int, rng: random.Random) -> list[tuple[str, str]]:
    """Scale-only padding, clearly labeled -- never used in the recall
    measurement, only to reach the >=100-vector latency-measurement floor."""
    filler = []
    for i in range(count):
        words = rng.choices(_FILLER_WORDS, k=8)
        filler.append((f"filler-{i}", " ".join(words)))
    return filler


def _percentiles(latencies_ms: list[float]) -> dict[str, float]:
    ordered = sorted(latencies_ms)
    return {
        "p50_ms": statistics.median(ordered),
        "p95_ms": statistics.quantiles(ordered, n=100)[94],
        "p99_ms": statistics.quantiles(ordered, n=100)[98],
    }


async def _open_bench_store(settings: VectorStoreSettings) -> PgVectorStore:
    await create_vector_store_test_table(settings)
    store = PgVectorStore(settings)
    await store.open()
    return store


async def main() -> None:
    rng = random.Random(1234)  # deterministic filler across runs

    real_tasks = load_real_slice()
    real_by_id = dict(real_tasks)
    filler_count = max(0, 100 - len(real_tasks))
    filler_tasks = _synthetic_filler(filler_count, rng)

    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],  # type: ignore[arg-type]
        table=_BENCH_TABLE,
    )
    store = await _open_bench_store(settings)
    try:
        await store.clear()  # dedicated bench table -- start from empty every run

        for task_id, text in real_tasks + filler_tasks:
            await store.upsert(task_id, embed(text))

        # Latency: query with every real task's own embedding (k=5), not
        # the filler's -- the filler only needs to exist to pad the table.
        # embed() runs outside the timer -- this measures search() alone,
        # not model inference too.
        latencies_ms = []
        for task_id, text in real_tasks:
            vector = embed(text)
            start = time.perf_counter()
            await store.search(vector, k=5)
            latencies_ms.append((time.perf_counter() - start) * 1000)

        # Recall: does the related real task outrank the control real task
        # among a query task's own top-5?
        recall_hits = 0
        recall_detail: list[dict[str, Any]] = []
        for pair in _RELATED_PAIRS:
            query_text = real_by_id[pair["query_id"]]
            search_results = await store.search(embed(query_text), k=5)
            ranked_ids = [r.task_id for r in search_results]
            related_rank = ranked_ids.index(pair["related_id"]) if pair["related_id"] in ranked_ids else None
            control_rank = ranked_ids.index(pair["control_id"]) if pair["control_id"] in ranked_ids else None
            hit = related_rank is not None and (control_rank is None or related_rank < control_rank)
            recall_hits += int(hit)
            recall_detail.append(
                {
                    "query_id": pair["query_id"],
                    "related_id": pair["related_id"],
                    "control_id": pair["control_id"],
                    "related_rank": related_rank,
                    "control_rank": control_rank,
                    "hit": hit,
                }
            )
    finally:
        await store.clear()
        await store.close()

    latency = _percentiles(latencies_ms)
    results = {
        "real_task_count": len(real_tasks),
        "filler_task_count": len(filler_tasks),
        "total_vector_count": len(real_tasks) + len(filler_tasks),
        "latency": latency,
        "recall_hit_rate": recall_hits / len(_RELATED_PAIRS),
        "recall_detail": recall_detail,
    }
    _RESULTS_FILE.write_text(json.dumps(results, indent=2))

    print(f"Real tasks (eval/real_slice/): {len(real_tasks)}")
    print(f"Synthetic filler (scale only): {len(filler_tasks)}")
    print(f"Total vectors:                 {len(real_tasks) + len(filler_tasks)}")
    print(
        "Latency: "
        f"p50={latency['p50_ms']:.2f}ms "
        f"p95={latency['p95_ms']:.2f}ms "
        f"p99={latency['p99_ms']:.2f}ms"
    )
    print(f"Recall (related-outranks-control, real pairs): {recall_hits}/{len(_RELATED_PAIRS)}")
    for d in recall_detail:
        status = "HIT" if d["hit"] else "MISS"
        print(
            f"  [{status}] {d['query_id']}: related={d['related_id']} (rank {d['related_rank']}), "
            f"control={d['control_id']} (rank {d['control_rank']})"
        )
    print(f"\nFull results written to {_RESULTS_FILE}")


if __name__ == "__main__":
    asyncio.run(main())
