"""
Vectors, semantic search, duplicate/related-task detection.

Public interface -- everything else in the app should only ever import
from this top-level package:

    from mind.embeddings import embed, SearchResult, VectorStore

`embed()` is the only module-level convenience function here: it's pure,
synchronous, CPU-only, and needs no store. Everything that talks to the
database goes through `PgVectorStore` (`mind.embeddings.pgvector_store`)
directly -- construct one with a `VectorStoreSettings` (see
`mind.config`) wherever it's needed, e.g. once in a FastAPI lifespan
handler, held on `app.state`, and passed to route handlers from there:

    from mind.config import get_settings
    from mind.embeddings.pgvector_store import PgVectorStore

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        store = PgVectorStore(get_settings().vector_store)
        await store.open()
        try:
            app.state.vector_store = store
            yield
        finally:
            await store.close()

There's no factory/singleton indirection anymore: `PgVectorStore` is the
only `VectorStore` implementation today, and constructing it needs
nothing beyond the settings object you already have. If a second backend
is ever added, callers switch which class they construct.

Backend: pgvector (decision + comparison in
`benchmarks/vector-store-comparison.md`).
Embedding model: a placeholder HashingVectorizer, not a real semantic
model yet, deliberately -- see `embeddings.py` and the comparison doc for
why.
Target: 10,000+ embedded tasks, semantic query at <300ms p95 (week 8 load
target, to be confirmed with mentors).
"""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.embeddings import embed

__all__ = [
    "SearchResult",
    "VectorStore",
    "embed",
]
