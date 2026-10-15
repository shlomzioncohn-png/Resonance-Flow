#!/usr/bin/env python3
"""#469: load eval/real_slice/'s 16 real AMI tasks into the real vector
store (whatever MIND_VECTOR_STORE__DSN/table Settings resolves to) --
unlike benchmarks/foundation_gate_benchmark.py, this does NOT clear the
table afterward, so a sample query (see query_sample.py) has something
real to find. Idempotent: upsert, safe to re-run.

Loads the same real_slice JSON files the same way
benchmarks/foundation_gate_benchmark.py does, duplicated rather than
imported -- a script run standalone (`python scripts/x.py`, not `python
-m`) doesn't have the repo root on sys.path, so it can't reach a
top-level `benchmarks` package the way `mind`'s own installed package can.

Usage (see src/mind/embeddings/README.md's "Foundation Gate benchmark"
section for the full from-nothing walkthrough):
    python scripts/load_real_slice.py
"""

import asyncio
import json
from pathlib import Path

from mind.config import get_settings
from mind.embeddings import embed
from mind.embeddings.pgvector_store import PgVectorStore

_REAL_SLICE_DIR = Path(__file__).resolve().parent.parent / "eval" / "real_slice"


def _load_real_slice() -> list[tuple[str, str]]:
    tasks = []
    for path in sorted(_REAL_SLICE_DIR.glob("*.json")):
        scenario = json.loads(path.read_text())
        for task in scenario["tasks"]:
            text = f"{task['title']} {task['description']}".strip()
            tasks.append((task["task_id"], text))
    return tasks


async def main() -> None:
    real_tasks = _load_real_slice()
    store = PgVectorStore(get_settings().vector_store)
    await store.open()
    try:
        for task_id, text in real_tasks:
            await store.upsert(task_id, embed(text))
    finally:
        await store.close()
    print(f"Loaded {len(real_tasks)} real tasks from eval/real_slice/ into the vector store.")


if __name__ == "__main__":
    asyncio.run(main())
