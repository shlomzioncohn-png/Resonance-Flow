"""#472: live semantic related-task search, callable from outside Mind
through R4's service layer (#450) -- see embeddings/README.md for the
endpoint shape to agree with her.

"Related" is a looser bar than #471's duplicate check on purpose: the
same pair (e.g. a prod/staging deploy of the same change) can be related
without being a duplicate. See embeddings/README.md "Threshold
calibration" for why this uses its own, lower threshold. Unlike #244/
#471, a completed task is not excluded here -- relatedness is about
topical context (what Lens/Inbox show alongside a task), not a live
blocking check, so a finished related task is still useful context.
"""

from mind.config import get_settings
from mind.embeddings.base import SearchResult, VectorStore

DEFAULT_K = 5  # Same cap as #245's default -- no evidence yet to pick a different number.


async def find_related(
    store: VectorStore, task_id: str, *, k: int = DEFAULT_K, threshold: float | None = None
) -> list[SearchResult]:
    """Up to k tasks related to task_id, ranked, task_id itself excluded.
    threshold=None reads Settings.related_task_threshold. A task_id with
    no embedding yet, an empty index, and no candidate clearing threshold
    all return [] -- never an error (#472's edge cases)."""
    if threshold is None:
        threshold = get_settings().related_task_threshold
    vector = await store.get_vector(task_id)
    if vector is None:
        return []
    candidates = await store.search(vector, k=k, exclude_task_id=task_id)
    return [c for c in candidates if c.similarity >= threshold]
