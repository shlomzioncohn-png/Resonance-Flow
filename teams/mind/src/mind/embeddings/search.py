"""Shared nearest-neighbor search behind duplicate detection (#471) and
dependency resolution (#245). No threshold -- callers apply their own."""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.embeddings import embed_async


async def nearest_tasks(
    store: VectorStore,
    text: str,
    *,
    k: int,
    is_query: bool = False,
    exclude_completed: bool = False,
    exclude_task_id: str | None = None,
) -> list[SearchResult]:
    """embed_async(text) then store.search(vector, k=k). is_query=True for
    #245 (asymmetric mention-vs-passage); False (default) for #471's
    symmetric document comparison. exclude_completed and exclude_task_id
    passed straight through to store.search() -- see its docstring."""
    vector = await embed_async(text, is_query=is_query)
    return await store.search(
        vector, k=k, exclude_completed=exclude_completed, exclude_task_id=exclude_task_id
    )
