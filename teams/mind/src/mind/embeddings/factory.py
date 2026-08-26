"""
The single switch that decides which VectorStore implementation backs
`upsert`/`search`. To migrate to a different database in the future:

    1. Write a new class in e.g. `qdrant_store.py` that implements
       `mind.embeddings.base.VectorStore`.
    2. Change the import + return line below.

That's it -- no other file in the app imports a database-specific class.
Moved from the PoC (`Choose a vector database/mind_vector_poc/mind_vector/factory.py`).

The import of `pgvector_store` is deferred to inside `get_store()`
(instead of at module top-level) precisely so that importing `factory`
-- or `base`, which it depends on -- never forces psycopg2 to be
importable; only actually calling `get_store()` does.
"""

from mind.embeddings.base import VectorStore


def get_store() -> VectorStore:
    from mind.embeddings.pgvector_store import PgVectorStore

    return PgVectorStore()
