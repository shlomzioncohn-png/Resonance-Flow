"""
Ingest pipeline: loader.fetch_real_slice() -> embed_batch() -> upsert
(source_ref as the id), under DEMO_TENANT_ID.

Run:  python -m mind.embeddings.ingest
"""

import asyncio

from mind.config import get_settings
from mind.embeddings import embed_batch_async
from mind.embeddings.base import DEMO_TENANT_ID
from mind.embeddings.loader import fetch_real_slice
from mind.embeddings.pgvector_store import PgVectorStore


async def main() -> None:
    # fetch_real_slice() can block on a network download.
    records = await asyncio.to_thread(fetch_real_slice)
    print(f"Loaded {len(records)} real records from the FUNSD manifest slice.")

    texts = [r.text for r in records]
    vectors = await embed_batch_async(texts)
    print(f"Embedded {len(vectors)} records (dim={len(vectors[0])}).")

    index = PgVectorStore(get_settings().vector_store)
    store = index.for_tenant(DEMO_TENANT_ID)
    try:
        await index.open()
        await asyncio.gather(
            *(store.upsert(record.source_ref, vector) for record, vector in zip(records, vectors))
        )
    finally:
        await index.close()
    print(f"Upserted {len(records)} rows (source_ref -> embedding) into pgvector.")


if __name__ == "__main__":
    asyncio.run(main())
