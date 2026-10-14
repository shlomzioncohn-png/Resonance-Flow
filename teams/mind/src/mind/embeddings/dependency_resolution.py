"""#245/#244/#471: shared nearest-neighbor resolution, via `nearest_tasks()`.

#245: a free-text dependency mention (Yonit's extraction has no task_id
for it) -> ranked candidates. Interface agreed with Yonit: plain list of
strings in, one ranked result list out per string, same order.
"""

import asyncio

from mind.config import get_settings
from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.search import nearest_tasks

DEFAULT_K = 5


async def resolve_dependency_targets(
    store: VectorStore,
    references: list[str],
    *,
    k: int = DEFAULT_K,
    threshold: float | None = None,
    is_query: bool = True,
) -> list[list[SearchResult]]:
    """Up to k candidates per reference, same order as the input. "No
    match" is the default: threshold=None reads Settings.dependency_match_threshold,
    not "no filtering" -- pass threshold=0.0 explicitly for the raw,
    unfiltered ranked list. is_query=True (default) is #245/#244's
    mention-vs-passage case; #471 passes is_query=False (full task vs
    full task -- symmetric, see nearest_tasks()). Propagates ValueError
    on empty text, same as embed()."""
    if threshold is None:
        threshold = get_settings().dependency_match_threshold
    # exclude_completed=True at the store level, not a post-hoc Python
    # filter here -- index_consumer.py never deletes completed tasks, only
    # flips the flag, and a small k (find_duplicate's k=1) could fetch
    # nothing but completed rows; filtering afterward would then discard
    # everything even with a real open match just outside that window.
    results = await asyncio.gather(
        *(
            nearest_tasks(store, text, k=k, is_query=is_query, exclude_completed=True)
            for text in references
        )
    )
    return [[c for c in candidates if c.similarity >= threshold] for candidates in results]
