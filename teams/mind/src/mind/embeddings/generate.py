"""
Real text -> embedding generation. Replaces the earlier `embeddings.py`
placeholder (HashingVectorizer) -- that file is gone; this is the real
model.

Model: sentence-transformers/all-MiniLM-L6-v2
  - Local, open-source (Apache-2.0), CPU-only -- no GPU, no network calls at
    inference time (weights are downloaded once and cached locally).
  - Output dimension: 384 (`EMBEDDING_DIM` below; the pgvector table
    schema in `pgvector_store.py` is created with `VECTOR(384)` to match).
  - English-only by design choice: MIND only needs English (confirmed with
    the team -- Hebrew support is explicitly out of scope), so no
    multilingual-capacity tradeoff was needed.
  - `BAAI/bge-small-en-v1.5` was evaluated first and preferred (it's tuned
    specifically for retrieval -- query-vs-passage matching, a better fit
    for this module's actual use case than MiniLM's generic sentence
    similarity) but its weights are reproducibly blocked by NetFree (a
    content filter on this dev machine) -- a `418 Blocked by NetFree`
    error fetching `model.safetensors`, while MiniLM downloads fine from
    the same CDN. MiniLM is used instead because it's actually available
    here; revisit BGE once that network restriction is lifted or this runs
    on a different machine.

Long-text handling
-------------------
all-MiniLM-L6-v2 has a native max sequence length of 256 word-piece
tokens. Text longer than that is **truncated**, not chunk-and-averaged.
Chosen because:
  - the current target content (FUNSD form-field text, task title +
    description) is almost always well under 256 tokens per record
  - chunk-and-average adds real complexity (chunk boundaries, weighting,
    re-aggregating multiple vectors into one) that has no payoff for
    content this short
This is set explicitly via `_model.max_seq_length` below rather than left
implicit, and `embed()`/`embed_batch()` are guaranteed not to raise on
long input -- they just silently truncate.

Revisit this choice if a long-document source becomes an embedding target
(e.g. full AMI meeting transcripts, which regularly exceed 256 tokens) --
chunk-and-average (or a long-context model) would then be worth the added
complexity.
"""

from sentence_transformers import SentenceTransformer

EMBEDDING_DIM = 384
MAX_SEQ_LENGTH = 256  # tokens; longer text is truncated, see module docstring

_model: SentenceTransformer | None = None


def _get_model() -> SentenceTransformer:
    # Lazy-loaded so importing this module (e.g. from tests that only need
    # the interface) doesn't force a model load / weight download.
    global _model
    if _model is None:
        _model = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")
        _model.max_seq_length = MAX_SEQ_LENGTH
    return _model


def embed(text: str) -> list[float]:
    """Turn `text` into a deterministic, unit-length embedding vector.

    Longer than MAX_SEQ_LENGTH tokens -> truncated, never raises.
    """
    if not isinstance(text, str):
        raise TypeError(f"embed() expects a str, got {type(text)}")
    vector = _get_model().encode(text, normalize_embeddings=True)
    return vector.tolist()


def embed_batch(texts: list[str]) -> list[list[float]]:
    """Batch version of embed() -- one model call for many texts, same
    truncation behavior per-text as embed()."""
    if not all(isinstance(t, str) for t in texts):
        raise TypeError("embed_batch() expects a list[str]")
    vectors = _get_model().encode(texts, normalize_embeddings=True)
    return vectors.tolist()
