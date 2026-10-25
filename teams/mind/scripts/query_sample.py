#!/usr/bin/env python3
"""#469: one sample similarity query against the real tasks loaded by
load_real_slice.py -- run that script first, or this finds nothing.
Deterministic (embed() has no randomness), so this always prints the
same ranking -- see src/mind/embeddings/README.md's "Foundation Gate
benchmark" section for the exact expected output.

Usage:
    python scripts/query_sample.py
"""

import asyncio

from mind.config import get_settings
from mind.embeddings import embed
from mind.embeddings.base import DEMO_TENANT_ID
from mind.embeddings.pgvector_store import PgVectorStore

_SAMPLE_QUERY = "Collect the team members' own seating preferences"


async def main() -> None:
    index = PgVectorStore(get_settings().vector_store)
    await index.open()
    try:
        results = await index.for_tenant(DEMO_TENANT_ID).search(embed(_SAMPLE_QUERY), k=3)
    finally:
        await index.close()

    print(f"Query: {_SAMPLE_QUERY!r}")
    for rank, r in enumerate(results, start=1):
        print(f"  {rank}. {r.task_id} (similarity={r.similarity:.4f})")


if __name__ == "__main__":
    asyncio.run(main())
