"""
embed(): turns a piece of text into a fixed-length vector.

This is currently a deterministic, fully offline placeholder -- not a
real semantic embedding model -- built on scikit-learn's HashingVectorizer.
It hashes words into a fixed number of buckets (EMBEDDING_DIM) and
produces a unit-length vector from those bucket counts (no TF-IDF
re-weighting is applied). Because it only looks at literal word overlap,
it has no notion of meaning: "car" and "automobile" produce unrelated
vectors, and two short paraphrases of the same idea can end up far apart
in vector space. See `benchmarks/vector-store-comparison.md` for the
measured impact of this limitation.

It exists so the rest of the vector-store interface (VectorStore,
PgVectorStore, upsert/search) can be built, tested, and validated end to
end without depending on downloading or serving a real model.

Swapping this out for a real model later (e.g. `sentence-transformers`,
`all-MiniLM-L6-v2`) is a one-function change: replace the body of `embed()`
below. Nothing in `pgvector_store.py`, `factory.py`, or any caller needs
to change, because they only ever see `list[float]` of length
EMBEDDING_DIM.
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
