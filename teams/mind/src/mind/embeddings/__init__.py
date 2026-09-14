"""
Vectors, semantic search, duplicate/related-task detection.

Public interface -- everything else in the app should only ever import
from this top-level package:

    from mind.embeddings import embed, embed_batch, upsert, search, delete, clear
    # or, from async code: embed_async, embed_batch_async (non-blocking)

Never import `base.py` / `pgvector_store.py` / `factory.py` / `generate.py`
directly. The concrete vector database is an implementation detail hidden
behind `factory.get_store()` + `base.VectorStore`; swapping it later means
changing those files, never anything that imports `mind.embeddings`.

Backend: pgvector (decision + comparison in
`benchmarks/vector-store-comparison.md`).
Embedding model: BAAI/bge-small-en-v1.5, 384-dim, local CPU-only -- see
`generate.py` for why, the query/passage asymmetry it requires, and how
long text is handled.
Target: 10,000+ embedded tasks, semantic query at <300ms p95 (week 8 load
target, to be confirmed with mentors).

`upsert`/`search`/`delete`/`clear` are `async def` -- the backing store
talks to Postgres over psycopg's async API so a slow query doesn't stall
the event loop out from under every other request FastAPI is serving
concurrently.
"""

import asyncio

from mind.embeddings.base import VectorStore
from mind.embeddings.config import DUPLICATE_SIMILARITY_THRESHOLD
from mind.embeddings.factory import get_store
from mind.embeddings.generate import embed, embed_async, embed_batch, embed_batch_async

__all__ = [
    "DUPLICATE_SIMILARITY_THRESHOLD",
    "clear",
    "delete",
    "embed",
    "embed_async",
    "embed_batch",
    "embed_batch_async",
    "search",
    "upsert",
]

_store = None
_store_lock = asyncio.Lock()


async def _get_store() -> VectorStore:
    # Lazy so `import mind.embeddings` (e.g. from tests that only exercise
    # embed()) never forces a live DB connection.
    #
    # Double-checked locking: two coroutines on the event loop could both
    # await past `_store is None` before either finishes constructing a
    # store. Without the lock, both could build their own PgVectorStore
    # (each opening its own connection pool and racing to run the
    # CREATE EXTENSION/TABLE/INDEX schema setup), and one of those stores
    # -- and its pool -- would just be silently discarded. The cheap
    # `if _store is None` check outside the lock keeps the lock itself
    # off the hot path once `_store` is set.
    global _store
    if _store is None:
        async with _store_lock:
            if _store is None:
                _store = get_store()
    return _store


async def upsert(task_id: str, vector: list[float]) -> None:
    """Save (or update) a vector under `task_id` in the vector store."""
    store = await _get_store()
    await store.upsert(task_id, vector)


async def search(query_vector: list[float], k: int) -> list[tuple[str, float]]:
    """Return the `k` (task_id, similarity) pairs most similar to
    `query_vector`, best match first. similarity is cosine similarity,
    comparable against DUPLICATE_SIMILARITY_THRESHOLD."""
    store = await _get_store()
    return await store.search(query_vector, k)


async def delete(task_id: str) -> None:
    """Remove the vector for `task_id`, if it exists. No-op if it doesn't."""
    store = await _get_store()
    await store.delete(task_id)


async def clear() -> None:
    """Remove every stored vector. Intended for test teardown, not production use."""
    store = await _get_store()
    await store.clear()
