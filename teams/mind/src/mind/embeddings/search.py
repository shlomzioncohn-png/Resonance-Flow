"""Shared nearest-neighbor search behind duplicate detection (#471) and
dependency resolution (#245). No threshold -- callers apply their own."""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.embeddings import embed


async def nearest_tasks(store: VectorStore, text: str, *, k: int) -> list[SearchResult]:
    """embed(text) then store.search(vector, k=k). Raises ValueError (from
    embed()) if text has no extractable tokens."""
    vector = embed(text)
    return await store.search(vector, k=k)
