"""
Vectors, semantic search, duplicate/related-task detection.

`embed()` is the module-level function here. `PgVectorStore`
(pgvector_store.py) is constructed directly wherever needed -- no
factory/singleton.
"""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.embeddings import embed
from mind.embeddings.search import nearest_tasks

__all__ = [
    "SearchResult",
    "VectorStore",
    "embed",
    "nearest_tasks",
]
