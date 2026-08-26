"""
embed(): turns a piece of text into a fixed-length vector.

Moved from the PoC (`Choose a vector database/mind_vector_poc/mind_vector/embeddings.py`).
Deliberately still the PoC's placeholder: scikit-learn's HashingVectorizer
+ TF-IDF re-weighting, a deterministic, 100%-offline, CPU-only technique
that turns text into a fixed-size vector based on word hashes. It is NOT a
semantic embedding model (it won't know that "car" and "automobile" are
related) -- it is a stand-in that let the PoC build and validate the
*interface* end-to-end. Not being swapped for a real model yet (tracked
separately) -- see `benchmarks/vector-store-comparison.md` for the
decision context, and the PoC's evaluation history for what that gap costs
in practice (near-zero recall on paraphrased duplicates at scale).

Swapping this out for a real model later (e.g. `sentence-transformers`,
`all-MiniLM-L6-v2`) is a one-function change: replace the body of `embed()`
below. Nothing in `store.py` or any caller changes, because they only ever
see `list[float]` of length EMBEDDING_DIM.
"""

from sklearn.feature_extraction.text import HashingVectorizer

EMBEDDING_DIM = 256

_vectorizer = HashingVectorizer(
    n_features=EMBEDDING_DIM,
    alternate_sign=False,
    norm="l2",
)


def embed(text: str) -> list[float]:
    """Turn `text` into a deterministic unit-length vector of EMBEDDING_DIM floats."""
    if not isinstance(text, str):
        raise TypeError(f"embed() expects a str, got {type(text)}")
    sparse_vec = _vectorizer.transform([text])
    return sparse_vec.toarray()[0].tolist()
