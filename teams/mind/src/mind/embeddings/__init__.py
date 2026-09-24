"""
Vectors, semantic search, duplicate/related-task detection.

Public interface -- everything else in the app should only ever import
from this top-level package:

    from mind.embeddings import embed, embed_batch, upsert, search, search_text, delete, clear, open, close
    # or, from async code: embed_async, embed_batch_async (non-blocking)

Searching by natural-language query text: use `search_text()`, not
`search(embed(query))`. BGE is asymmetric -- a query needs the instruction
prefix `embed()`'s `is_query=True` adds, and a caller composing `embed()` +
`search()` by hand can forget that flag with no error, just a silently
inflated similarity score (see `generate.py`'s module docstring). Passage-
to-passage comparisons (e.g. duplicate/related-task detection, comparing
one stored task's embedding against the others) should NOT use
`search_text()` -- embed both sides as passages (`is_query=False`, the
default) and call `search()` directly with the resulting vector.

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

`upsert`/`search`/`delete`/`clear`/`open`/`close` are `async def` -- the
backing store talks to Postgres over psycopg's async API so a slow query
doesn't stall the event loop out from under every other request FastAPI
is serving concurrently.

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

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.config import DUPLICATE_SIMILARITY_THRESHOLD
from mind.embeddings.factory import get_store
from mind.embeddings.generate import embed, embed_async, embed_batch, embed_batch_async

__all__ = [
    "DUPLICATE_SIMILARITY_THRESHOLD",
    "SearchResult",
    "clear",
    "close",
    "delete",
    "embed",
    "embed_async",
    "embed_batch",
    "embed_batch_async",
    "open",
    "search",
    "search_text",
    "upsert",
]

_store: VectorStore | None = None


async def _get_store() -> VectorStore:
    # Lazy so `import mind.embeddings` never forces a live DB connection.
    # No lock needed: get_store() has no `await` in it, so nothing can
    # interleave between the check and the assignment on one event loop
    # (contrast PgVectorStore._ensure_ready, which awaits pool.open() and
    # genuinely needs one).
    global _store
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
    against DUPLICATE_SIMILARITY_THRESHOLD.

    For callers that already have a vector to search with -- e.g.
    passage-to-passage comparisons like duplicate/related-task detection,
    where neither side should get the query instruction prefix. Callers
    searching by natural-language query text should use `search_text()`
    instead, not `embed(text, is_query=True)` + this."""
    store = await _get_store()
    return await store.search(query_vector, k)


async def search_text(query: str, k: int) -> list[SearchResult]:
    """Search by natural-language query text -- embeds `query` as a query
    (not a passage) and searches with the result. Use this instead of
    `search(embed(query))`: composing those by hand means remembering
    `embed(..., is_query=True)` yourself, and forgetting it doesn't raise
    -- it just silently inflates the similarity score (see generate.py's
    module docstring's "Query/passage asymmetry" section), which matters
    because DUPLICATE_SIMILARITY_THRESHOLD compares against this exact
    number.

    Not for passage-to-passage comparisons (e.g. duplicate detection) --
    those should call `search()` directly with a passage-mode vector."""
    vector = await embed_async(query, is_query=True)
    return await search(vector, k)


async def delete(task_id: str) -> None:
    """Remove the vector for `task_id`, if it exists. No-op if it doesn't."""
    store = await _get_store()
    await store.delete(task_id)


async def clear() -> None:
    """Remove every stored vector. Intended for test teardown, not production use."""
    store = await _get_store()
    await store.clear()
