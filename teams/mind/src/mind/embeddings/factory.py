"""
The single switch that decides which VectorStore implementation backs
`upsert`/`search`. To migrate to a different database in the future:

    1. Write a new class in e.g. `qdrant_store.py` that implements
       `mind.embeddings.base.VectorStore`.
    2. Change the import + return line below.

That's it -- no other file in the app imports a database-specific class.
"""

from mind.embeddings.base import VectorStore


def get_store() -> VectorStore:
    # Imported here, not at module top-level, so importing `factory` never
    # requires psycopg -- only calling get_store() does.
    from mind.embeddings.pgvector_store import PgVectorStore

    return PgVectorStore()
