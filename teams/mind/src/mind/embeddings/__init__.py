"""
Vectors, semantic search, duplicate/related-task detection.

Public interface -- everything else in the app should only ever import
from this top-level package:

    from mind.embeddings import embed, upsert, search, delete, clear, open, close

Never import `base.py` / `pgvector_store.py` / `factory.py` directly. The
concrete vector database is an implementation detail hidden behind
`factory.get_store()` + `base.VectorStore`; swapping it later means
changing those files, never anything that imports `mind.embeddings`.

Backend: pgvector (decision + comparison in
`benchmarks/vector-store-comparison.md`).
Embedding model: a placeholder HashingVectorizer, not a real semantic
model yet, deliberately -- see `embeddings.py` and the comparison doc for
why.
Target: 10,000+ embedded tasks, semantic query at <300ms p95 (week 8 load
target, to be confirmed with mentors).

`upsert`/`search`/`delete`/`clear` are `async def` -- the backing store
talks to Postgres over psycopg's async API so a slow query doesn't stall
the event loop out from under every other request FastAPI is serving
concurrently.

Pool lifetime: call `open()`/`close()` from a FastAPI lifespan handler to
tie the backing store's connection pool to the app's own event loop and
lifetime, rather than letting it lazily initialize on whichever loop
happens to make the first call:

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        await mind.embeddings.open()
        yield
        await mind.embeddings.close()
"""

import asyncio

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.config import DUPLICATE_SIMILARITY_THRESHOLD
from mind.embeddings.embeddings import embed
from mind.embeddings.factory import get_store

__all__ = [
    "DUPLICATE_SIMILARITY_THRESHOLD",
    "SearchResult",
    "clear",
    "close",
    "delete",
    "embed",
    "open",
    "search",
    "upsert",
]

_store: VectorStore | None = None
_store_lock = asyncio.Lock()


async def _get_store() -> VectorStore:
    # Lazy so `import mind.embeddings` never forces a live DB connection.
    # Double-checked locking: without the lock, two coroutines could both
    # await past `_store is None` and each construct (and leak) their own
    # store; the outer check keeps the lock off the hot path once it's set.
    global _store
    if _store is None:
        async with _store_lock:
            if _store is None:
                _store = get_store()
    return _store


async def open() -> None:
    """Eagerly initialize the vector store. See the package docstring's
    "Pool lifetime" section for why you'd call this explicitly."""
    store = await _get_store()
    await store.open()


async def close() -> None:
    """Release the vector store's resources (e.g. close its connection
    pool). Only meaningful after open() or any other call that triggered
    lazy initialization; safe to call otherwise too."""
    global _store
    if _store is not None:
        await _store.close()
        _store = None


async def upsert(task_id: str, vector: list[float]) -> None:
    """Save (or update) a vector under `task_id` in the vector store."""
    store = await _get_store()
    await store.upsert(task_id, vector)


async def search(query_vector: list[float], k: int) -> list[SearchResult]:
    """Return the `k` results most similar to `query_vector`, best match
    first. Each result's `.similarity` is cosine similarity, comparable
    against DUPLICATE_SIMILARITY_THRESHOLD."""
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
