"""#245: resolve a free-text dependency mention (Yonit's extraction has
no task_id for it) into ranked candidate tasks, via `nearest_tasks()`.
No match/no-match decision here -- that's #244, on top of these scores.

Interface agreed with Yonit: plain list of strings in, one ranked result
list out per string, same order.
"""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.search import nearest_tasks

# Not #471's k=1 -- a guess, the issue doesn't specify a number.
DEFAULT_K = 5


async def resolve_dependency_targets(
    store: VectorStore, references: list[str], *, k: int = DEFAULT_K
) -> list[list[SearchResult]]:
    """Up to k candidates per reference, same order as the input. Empty
    list = no match. Propagates ValueError on empty text, same as embed()."""
    results = []
    for text in references:
        # is_query=True: a mention vs. full task descriptions (see search.py).
        results.append(await nearest_tasks(store, text, k=k, is_query=True))
    return results
