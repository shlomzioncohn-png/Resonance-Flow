"""The one shared nearest-neighbor search primitive behind both duplicate
detection (#471) and dependency-target resolution (#245). No threshold, no
decision -- callers compare the returned similarities against their own
cutoff (duplicate detection and dependency resolution need different bars).

Deliberately does not wrap itself in `mind.api.instrumentation`'s
`instrumented_call` -- `embeddings` importing from `api` would invert the
codebase's existing dependency direction. Call sites wrap themselves
instead:

    with instrumented_call("duplicate_check"):
        results = await nearest_tasks(store, f"{title} {description}", k=1)
"""

from mind.embeddings.base import SearchResult, VectorStore
from mind.embeddings.embeddings import embed


async def nearest_tasks(store: VectorStore, text: str, *, k: int) -> list[SearchResult]:
    """embed(text) then store.search(vector, k=k). Raises ValueError (from
    embed()) if text has no extractable tokens."""
    vector = embed(text)
    return await store.search(vector, k=k)
