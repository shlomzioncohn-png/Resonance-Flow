"""
Ingest pipeline: loader.fetch_real_slice() -> embed_batch() -> upsert
(source_ref as the id).

Run:  python -m mind.embeddings.ingest
"""

from mind.embeddings import embed_batch, upsert
from mind.embeddings.loader import fetch_real_slice


def main():
    records = fetch_real_slice()
    print(f"Loaded {len(records)} real records from the FUNSD manifest slice.")

    texts = [r.text for r in records]
    vectors = embed_batch(texts)
    print(f"Embedded {len(vectors)} records (dim={len(vectors[0])}).")

    for record, vector in zip(records, vectors):
        upsert(record.source_ref, vector)
    print(f"Upserted {len(records)} rows (source_ref -> embedding) into pgvector.")


if __name__ == "__main__":
    main()
