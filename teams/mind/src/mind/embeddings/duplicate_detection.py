"""#471: duplicate-task check against the index, before task.creation.requested
(#353). R2's pipeline (#350) owns what happens with the signal."""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.dependency_resolution import resolve_dependency_targets
from mind.embeddings.embeddings import task_text


async def find_duplicate(
    store: VectorStore, title: str, description: str, *, threshold: float
) -> SearchResult | None:
    """Best match for this candidate task, if it clears threshold. Full
    task vs full task (is_query=False) -- not #245's mention-vs-passage case."""
    text = task_text(title, description)
    [candidates] = await resolve_dependency_targets(store, [text], k=1, threshold=threshold, is_query=False)
    return candidates[0] if candidates else None
