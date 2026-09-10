"""
Ingest pipeline: loader.fetch_real_slice() -> embed_batch() -> upsert
(source_ref as the id).

Run:  python -m mind.embeddings.ingest
"""

import asyncio

from mind.embeddings import embed_batch, upsert
from mind.embeddings.loader import fetch_real_slice


async def main() -> None:
    # fetch_real_slice() can block on a network download (see loader.py's
    # _fetch_missing_files) -- offload it to a thread so it doesn't stall
    # the event loop this script's own asyncio.run() drives.
    records = await asyncio.to_thread(fetch_real_slice)
    print(f"Loaded {len(records)} real records from the FUNSD manifest slice.")

    texts = [r.text for r in records]
    vectors = embed_batch(texts)
    print(f"Embedded {len(vectors)} records (dim={len(vectors[0])}).")

    await asyncio.gather(
        *(upsert(record.source_ref, vector) for record, vector in zip(records, vectors))
    )
    print(f"Upserted {len(records)} rows (source_ref -> embedding) into pgvector.")


if __name__ == "__main__":
    asyncio.run(main())
