"""
Real text -> embedding generation. Replaces the earlier `embeddings.py`
placeholder (HashingVectorizer) -- that file is gone; this is the real
model.

Model: BAAI/bge-small-en-v1.5
  - Local, open-source (MIT), CPU-only -- no GPU. Weights ship as a local
    folder under `models/bge-small-en-v1.5/` (gitignored, never committed
    -- see config.EMBEDDING_MODEL_LOCAL_DIR); when present, loading makes
    no network call at all. Falls back to downloading
    `config.EMBEDDING_MODEL_NAME` from the Hugging Face Hub if that folder
    isn't there.
  - Output dimension: 384 (`config.EMBEDDING_DIM`; the pgvector table
    schema in `pgvector_store.py` is created with `VECTOR(384)` to match).
  - English-only by design choice: MIND only needs English (confirmed with
    the team -- Hebrew support is explicitly out of scope), so no
    multilingual-capacity tradeoff was needed.
  - Chosen over a generic sentence-similarity model (e.g. all-MiniLM-L6-v2,
    an earlier candidate) because BGE is trained specifically for
    *retrieval* -- matching a short query against a corpus of longer
    passages -- which is exactly this module's use case (a query
    embedding searching a store of task/record embeddings), not just "are
    these two sentences similar".

Query/passage asymmetry
------------------------
BGE is an *asymmetric* retrieval model: the query side and the passage
(stored document) side are NOT encoded the same way.
  - Passages (what gets `upsert`-ed into the store) are encoded as-is --
    `is_query=False`, the default.
  - Queries (what gets `search`-ed with) must have
    `config.BGE_QUERY_INSTRUCTION` prepended first, or retrieval quality
    drops -- this is how the model was trained and is BAAI's documented
    usage. Callers indexing records leave `is_query` at its default;
    callers embedding a search query must pass `is_query=True`.

Long-text handling
-------------------
bge-small-en-v1.5 has a native max sequence length of 512 word-piece
tokens. Text longer than that is **truncated**, not chunk-and-averaged.
Chosen because:
  - the current target content (FUNSD form-field text, task title +
    description) is almost always well under 512 tokens per record
  - chunk-and-average adds real complexity (chunk boundaries, weighting,
    re-aggregating multiple vectors into one) that has no payoff for
    content this short
This is set explicitly via `_model.max_seq_length` below rather than left
implicit, and `embed()`/`embed_batch()` are guaranteed not to raise on
long input -- they just silently truncate.

Revisit this choice if a long-document source becomes an embedding target
(e.g. full AMI meeting transcripts, which regularly exceed 512 tokens) --
chunk-and-average (or a long-context model) would then be worth the added
complexity.

Calling from async code
------------------------
`embed()`/`embed_batch()` are plain `def`, not `async def` -- model
inference is CPU-bound, not I/O, so there's nothing to `await`. But that
also means they're blocking calls: running one directly inside an
`async def` route handler (the exact pattern in this package's README)
would stall the event loop for the duration of inference, the same
problem `pgvector_store.py` was migrated off psycopg2 to avoid -- just on
the embedding side instead of the DB side. Use `embed_async()` /
`embed_batch_async()` below from any `async def` caller instead; they
offload the same call to a worker thread via `asyncio.to_thread()` so the
event loop stays free to serve other requests while inference runs.
"""

import asyncio

from sentence_transformers import SentenceTransformer

from mind.embeddings.config import (
    BGE_QUERY_INSTRUCTION,
    EMBEDDING_MODEL_LOCAL_DIR,
    EMBEDDING_MODEL_NAME,
    MAX_SEQ_LENGTH,
)

_model: SentenceTransformer | None = None


def _get_model() -> SentenceTransformer:
    # Lazy-loaded so importing this module (e.g. from tests that only need
    # the interface) doesn't force a model load / weight download.
    global _model
    if _model is None:
        model_source = (
            str(EMBEDDING_MODEL_LOCAL_DIR)
            if EMBEDDING_MODEL_LOCAL_DIR.is_dir()
            else EMBEDDING_MODEL_NAME
        )
        _model = SentenceTransformer(model_source)
        _model.max_seq_length = MAX_SEQ_LENGTH
    return _model


def embed(text: str, is_query: bool = False) -> list[float]:
    """Turn `text` into a deterministic, unit-length embedding vector.

    `is_query=True` for search queries (adds the BGE query instruction
    prefix); leave `False` (default) when indexing a record for storage.
    Longer than MAX_SEQ_LENGTH tokens -> truncated, never raises.
    """
    if not isinstance(text, str):
        raise TypeError(f"embed() expects a str, got {type(text)}")
    if is_query:
        text = BGE_QUERY_INSTRUCTION + text
    vector = _get_model().encode(text, normalize_embeddings=True)
    return vector.tolist()


def embed_batch(texts: list[str], is_query: bool = False) -> list[list[float]]:
    """Batch version of embed() -- one model call for many texts, same
    is_query / truncation behavior per-text as embed()."""
    if not all(isinstance(t, str) for t in texts):
        raise TypeError("embed_batch() expects a list[str]")
    if is_query:
        texts = [BGE_QUERY_INSTRUCTION + t for t in texts]
    vectors = _get_model().encode(texts, normalize_embeddings=True)
    return vectors.tolist()


async def embed_async(text: str, is_query: bool = False) -> list[float]:
    """Non-blocking wrapper for embed() -- use this instead of embed()
    when calling from an `async def` route handler, so the CPU-bound
    model inference doesn't stall the event loop. See the module
    docstring's "Calling from async code" section."""
    return await asyncio.to_thread(embed, text, is_query)


async def embed_batch_async(texts: list[str], is_query: bool = False) -> list[list[float]]:
    """Non-blocking wrapper for embed_batch() -- see embed_async()."""
    return await asyncio.to_thread(embed_batch, texts, is_query)
