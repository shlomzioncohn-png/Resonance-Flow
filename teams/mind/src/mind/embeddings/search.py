"""Shared nearest-neighbor search behind duplicate detection (#471) and
dependency resolution (#245). No threshold -- callers apply their own."""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.embeddings import embed_async


async def nearest_tasks(
    store: VectorStore, text: str, *, k: int, is_query: bool = False
) -> list[SearchResult]:
    """embed_async(text) then store.search(vector, k=k). Raises ValueError
    (from embed()) if text has no extractable tokens.

    is_query matches embed()'s own flag: False (default) for #471's
    symmetric document-vs-document duplicate check; True for #245's
    free-text mention searched against full task descriptions, the same
    asymmetric query-vs-passage shape store.search_text() handles."""
    vector = await embed_async(text, is_query=is_query)
    return await store.search(vector, k=k)
