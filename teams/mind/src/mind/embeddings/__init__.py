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

Pool lifetime: `open()`/`close()` let a caller (e.g. a FastAPI lifespan
handler) control exactly when the backing store's resources -- e.g. a
connection pool's background tasks -- are created and torn down, tied to
the app's lifetime on a known event loop:

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        await mind.embeddings.open()
        yield
        await mind.embeddings.close()

If you never call open(), the store still initializes itself lazily on
first upsert/search/delete/clear -- fine for standalone scripts, but for
a long-running server prefer calling open() explicitly: a pool created by
accident on whichever event loop happens to make the first call, instead
of deliberately on the server's own loop, can end up with its background
tasks (reconnect, min-size top-up, connection recycling) pinned to the
wrong loop.
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
