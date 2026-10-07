"""#245: resolve a free-text dependency reference against the vector store.

Yonit's extraction flags that a task has a dependency and pulls out a
free-text description of it (e.g. "the DB upgrade"), but has no task_id --
nothing in her code knows which existing task that text refers to. This
module turns that text into a ranked list of candidate task_ids with
similarity scores, via `nearest_tasks()` (the same shared primitive #471's
duplicate detection uses). It makes no match/no-match decision itself --
that's #244's job, using whatever confidence rule it defines on top of
these scores -- and applies no threshold, deliberately: "strong match",
"weak match", and "no match" are about the scores this returns, not a
cutoff enforced here.

Interface agreed with Yonit: a plain list of strings in -- each one
already the free text to resolve, no wrapping object. One ranked result
list out per input string, same order, best match first.
"""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.search import nearest_tasks

# k=5, not #471's k=1: duplicate detection wants its single best candidate;
# dependency resolution hands a ranked list to #244/Chain, which needs
# more than one candidate to judge confidence against (see #244 and #245's
# own issue text: "a ranked list of potential task_ids").
DEFAULT_K = 5


async def resolve_dependency_targets(
    store: VectorStore, references: list[str], *, k: int = DEFAULT_K
) -> list[list[SearchResult]]:
    """For each reference string, search the store for up to k candidate
    tasks it might refer to. Returns one ranked list per input reference,
    in the same order -- callers (#244) apply their own confidence logic
    to the scores; an empty list means the store had nothing to match
    (the "no match" case), not an error.

    Propagates ValueError (from embed(), via nearest_tasks()) if a
    reference is empty/whitespace-only -- same semantic guard as
    everywhere else text gets embedded, not special-cased here.
    """
    results = []
    for text in references:
        results.append(await nearest_tasks(store, text, k=k))
    return results
