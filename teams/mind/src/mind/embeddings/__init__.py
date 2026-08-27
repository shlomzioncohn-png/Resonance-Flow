"""
Vectors, semantic search, duplicate/related-task detection.

Public interface -- everything else in the app should only ever import
from this top-level package:

    from mind.embeddings import embed, upsert, search

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
"""

from mind.embeddings.embeddings import embed
from mind.embeddings.factory import get_store
from mind.embeddings.config import DUPLICATE_SIMILARITY_THRESHOLD

__all__ = [
    "embed",
    "upsert",
    "search",
    "DUPLICATE_SIMILARITY_THRESHOLD",
]

_store = None


def _get_store():
    # Lazy so `import mind.embeddings` (e.g. from tests that only exercise
    # embed()) never forces a live DB connection.
    global _store
    if _store is None:
        _store = get_store()
    return _store


def upsert(task_id: str, vector: list[float]) -> None:
    """Save (or update) a vector under `task_id` in the vector store."""
    _get_store().upsert(task_id, vector)


def search(query_vector: list[float], k: int) -> list[str]:
    """Return the `k` task_ids whose vectors are most similar to `query_vector`."""
    return _get_store().search(query_vector, k)
