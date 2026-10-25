"""similarity(a, b) -> float: how alike two sentences are, 0..1."""

from mind.embeddings.embeddings import embed


def similarity(a: str, b: str) -> float:
    vec_a = embed(a)
    vec_b = embed(b)
    # embed() returns normalized vectors, so the dot product is the cosine
    # similarity directly. Clamped to [0, 1]: real sentences practically
    # never land negative, but the type promises 0..1 regardless.
    cosine = sum(x * y for x, y in zip(vec_a, vec_b))
    return max(0.0, min(1.0, cosine))
