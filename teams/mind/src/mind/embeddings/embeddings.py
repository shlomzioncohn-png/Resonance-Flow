"""
embed(): turns a piece of text into a fixed-length vector.

Deterministic, fully offline placeholder (scikit-learn's HashingVectorizer)
-- not a real semantic model, so it has no notion of meaning: "car" and
"automobile" hash to unrelated vectors. See
`benchmarks/vector-store-comparison.md` for the measured impact. It lets
the rest of the interface be built and tested without a real model;
swapping one in later only means replacing `embed()`'s body, since every
caller only ever sees `list[float]` of length EMBEDDING_DIM.
"""

from sklearn.feature_extraction.text import HashingVectorizer

from mind.embeddings.config import EMBEDDING_DIM

_vectorizer = HashingVectorizer(
    n_features=EMBEDDING_DIM,
    alternate_sign=False,
    norm="l2",
)


def embed(text: str) -> list[float]:
    """Turn `text` into a deterministic unit-length vector of EMBEDDING_DIM floats.

    Raises ValueError if `text` has no extractable tokens (e.g. empty, or
    only punctuation/whitespace). HashingVectorizer's `norm="l2"` can't
    normalize an all-zero row and silently returns it as-is; pgvector's
    cosine-distance operator then divides by that vector's zero norm and
    returns NaN, which sorts unpredictably and corrupts search results.
    Failing fast here surfaces the problem at the caller instead.
    """
    sparse_vec = _vectorizer.transform([text])
    vector = sparse_vec.toarray()[0]
    if not vector.any():
        raise ValueError(f"embed() got text with no extractable tokens: {text!r}")
    return vector.tolist()
