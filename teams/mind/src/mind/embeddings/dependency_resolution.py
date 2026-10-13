"""#245/#244/#471: shared nearest-neighbor resolution, via `nearest_tasks()`.

#245: a free-text dependency mention (Yonit's extraction has no task_id
for it) -> ranked candidates. Interface agreed with Yonit: plain list of
strings in, one ranked result list out per string, same order.
"""

import asyncio

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
    """Up to k candidates per reference, same order as the input. #245's
    default (threshold=None): no filtering, Yonit decides. #244: pass
    Settings.dependency_match_threshold. is_query=True (default) is #245/
    #244's mention-vs-passage case; #471 passes is_query=False (full task
    vs full task -- symmetric, see nearest_tasks()). Propagates ValueError
    on empty text, same as embed()."""
    results = await asyncio.gather(
        *(nearest_tasks(store, text, k=k, is_query=is_query) for text in references)
    )
    if threshold is None:
        return results
    return [[c for c in candidates if c.similarity >= threshold] for candidates in results]
