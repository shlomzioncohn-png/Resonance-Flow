"""
Vectors, semantic search, duplicate/related-task detection.

`embed()`/`embed_batch()` (+ async variants) are the module-level
functions here. `PgVectorStore` (pgvector_store.py) is constructed
directly wherever needed -- no factory/singleton.
"""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.dependency_resolution import resolve_dependency_targets
from mind.embeddings.embeddings import embed, embed_async, embed_batch, embed_batch_async
from mind.embeddings.search import nearest_tasks

__all__ = [
    "SearchResult",
    "VectorStore",
    "embed",
    "embed_async",
    "embed_batch",
    "embed_batch_async",
    "nearest_tasks",
    "resolve_dependency_targets",
]
