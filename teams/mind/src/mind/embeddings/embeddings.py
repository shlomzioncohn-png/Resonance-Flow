"""
embed(): deterministic, offline placeholder (HashingVectorizer) -- not a
real semantic model. See benchmarks/vector-store-comparison.md.
"""

from sklearn.feature_extraction.text import HashingVectorizer

# Must match VectorStoreSettings.embedding_dim's default.
EMBEDDING_DIM = 256

_vectorizer = HashingVectorizer(
    n_features=EMBEDDING_DIM,
    alternate_sign=False,
    norm="l2",
)


def embed(text: str) -> list[float]:
    """Deterministic unit-length vector. Raises ValueError if `text` has
    no extractable tokens -- an all-zero vector would give NaN similarity."""
    sparse_vec = _vectorizer.transform([text])
    vector = sparse_vec.toarray()[0]
    if not vector.any():
        raise ValueError(f"embed() got text with no extractable tokens: {text!r}")
    return vector.tolist()
