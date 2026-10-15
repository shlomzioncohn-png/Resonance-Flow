"""#472: related-task search, see embeddings/README.md."""

from mind.config import get_settings
from mind.embeddings.base import SearchResult, VectorStore

DEFAULT_K = 5


async def find_related(
    store: VectorStore, task_id: str, *, k: int = DEFAULT_K, threshold: float | None = None
) -> list[SearchResult]:
    """Up to k related tasks, task_id itself excluded. [] on no embedding,
    empty index, or nothing above threshold -- never an error."""
    if k <= 0:
        raise ValueError(f"k must be positive, got {k}")
    if threshold is None:
        threshold = get_settings().related_task_threshold
    vector = await store.get_vector(task_id)
    if vector is None:
        return []
    candidates = await store.search(vector, k=k, exclude_task_id=task_id)
    return [c for c in candidates if c.similarity >= threshold]
