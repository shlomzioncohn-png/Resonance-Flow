"""
Real text -> embedding generation. Replaces the earlier HashingVectorizer
placeholder (see git history) -- this is the real model.

Model: BAAI/bge-small-en-v1.5
  - Local, open-source (MIT), CPU-only -- no GPU. Reads `MODEL_CACHE_DIR`
    (`mind.config.EmbeddingModelSettings`); if set and populated (see
    `models/README.md`), no network call. Otherwise downloads from the
    Hugging Face Hub -- today's Docker behavior, not yet a decided one.
  - Output dimension: 384 (EMBEDDING_DIM; must match
    `VectorStoreSettings.embedding_dim`, since the pgvector table schema
    in `pgvector_store.py` is created with `VECTOR(embedding_dim)`).
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
  - Passages (stored records, duplicate-detection comparisons) are
    encoded as-is -- `is_query=False`, the default.
  - Queries need BGE_QUERY_INSTRUCTION prepended, or retrieval quality
    drops. Forgetting it doesn't raise -- it inflates the similarity
    score (measured: up to 0.11), which matters against
    `duplicate_similarity_threshold`. `is_query` is keyword-only; prefer
    `PgVectorStore.search_text()` over `search(embed(...))` for queries.

Long-text handling
-------------------
bge-small-en-v1.5 has a native max sequence length of 512 word-piece
tokens. Text longer than that is **truncated**, not chunk-and-averaged.
Chosen because the current target content (task title + description) is
almost always well under 512 tokens per record, and chunk-and-average adds
real complexity (chunk boundaries, weighting, re-aggregating multiple
vectors into one) that has no payoff for content this short. This is set
explicitly via `_model.max_seq_length` below rather than left implicit,
and `embed()`/`embed_batch()` are guaranteed not to raise on long input --
they just silently truncate.

Revisit this choice if a long-document source becomes an embedding target
(e.g. full AMI meeting transcripts, which regularly exceed 512 tokens) --
chunk-and-average (or a long-context model) would then be worth the added
complexity.

Empty/no-content text
-----------------------
embed()/embed_batch() raise ValueError on empty or whitespace-only text --
not a model limitation (a neural encoder happily returns *some* vector for
""), but a semantic guard: a task with no title or description has nothing
meaningful to index, and callers (index_consumer.py) rely on this to
decide to log-and-skip rather than store a meaningless embedding.

Calling from async code
------------------------
`embed()`/`embed_batch()` are plain `def`, not `async def` -- model
inference is CPU-bound, not I/O, so there's nothing to `await`. But that
also means they're blocking calls: running one directly inside an
`async def` route handler would stall the event loop for the duration of
inference (tens of ms with this model, unlike the old placeholder's
sub-millisecond hash) -- the same problem `pgvector_store.py` was built
async to avoid, just on the embedding side instead of the DB side. Use
`embed_async()` / `embed_batch_async()` below from any `async def` caller
instead; they offload the same call to a worker thread via
`asyncio.to_thread()` so the event loop stays free to serve other work
while inference runs.
"""

import asyncio
import threading
from pathlib import Path

from sentence_transformers import SentenceTransformer

from mind.config import EmbeddingModelSettings

# Must match VectorStoreSettings.embedding_dim's default -- both describe
# the same fixed fact about this model.
EMBEDDING_DIM = 384
MAX_SEQ_LENGTH = 512  # tokens; longer text is truncated, see module docstring

# BGE is an asymmetric retrieval model: queries need this instruction
# prefix prepended before encoding; stored passages/documents do not.
BGE_QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: "

# EmbeddingModelSettings, not Settings -- no DSN needed.
_embedding_model_settings = EmbeddingModelSettings()
EMBEDDING_MODEL_NAME = _embedding_model_settings.name
# None if MODEL_CACHE_DIR isn't set. Not __file__-based -- that breaks
# once pip-installed (e.g. in Docker) rather than run editable.
EMBEDDING_MODEL_LOCAL_DIR: Path | None = (
    Path(_embedding_model_settings.model_cache_dir) / EMBEDDING_MODEL_NAME.rsplit("/", 1)[-1]
    if _embedding_model_settings.model_cache_dir
    else None
)

_model: SentenceTransformer | None = None
_model_lock = threading.Lock()


def _get_model() -> SentenceTransformer:
    # Lazy-loaded so importing this module (e.g. from tests that only need
    # the interface) doesn't force a model load / weight download.
    #
    # threading.Lock, not asyncio.Lock: embed_async()/embed_batch_async()
    # run this via asyncio.to_thread() on real OS worker threads, not
    # coroutines on one event loop -- an asyncio.Lock doesn't coordinate
    # across threads. Without this lock, two concurrent embed_async()
    # calls could both see `_model is None` and both construct a
    # SentenceTransformer (each doing the same file I/O / possible
    # download), silently discarding one instance.
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                model_source = (
                    str(EMBEDDING_MODEL_LOCAL_DIR)
                    if EMBEDDING_MODEL_LOCAL_DIR is not None and EMBEDDING_MODEL_LOCAL_DIR.is_dir()
                    else EMBEDDING_MODEL_NAME
                )
                _model = SentenceTransformer(model_source)
                _model.max_seq_length = MAX_SEQ_LENGTH
    return _model


def embed(text: str, *, is_query: bool = False) -> list[float]:
    """Turn `text` into a deterministic, unit-length embedding vector.

    `is_query=True` for search queries (adds the BGE query instruction
    prefix); leave `False` (default) when indexing a record for storage,
    or comparing one stored record's text against another's. Raises
    ValueError if `text` has no non-whitespace content -- see the module
    docstring's "Empty/no-content text" section.
    """
    if not text.strip():
        raise ValueError(f"embed() got text with no extractable content: {text!r}")
    if is_query:
        text = BGE_QUERY_INSTRUCTION + text
    vector = _get_model().encode(text, normalize_embeddings=True)
    return vector.tolist()


def embed_batch(texts: list[str], *, is_query: bool = False) -> list[list[float]]:
    """Batch version of embed() -- one model call for many texts, same
    is_query / truncation / empty-text behavior per-text as embed()."""
    if not texts:
        return []
    for text in texts:
        if not text.strip():
            raise ValueError(f"embed_batch() got text with no extractable content: {text!r}")
    if is_query:
        texts = [BGE_QUERY_INSTRUCTION + t for t in texts]
    vectors = _get_model().encode(texts, normalize_embeddings=True)
    return vectors.tolist()


async def embed_async(text: str, *, is_query: bool = False) -> list[float]:
    """Non-blocking wrapper for embed() -- use this instead of embed()
    when calling from an `async def` route handler, so the CPU-bound
    model inference doesn't stall the event loop. See the module
    docstring's "Calling from async code" section."""
    return await asyncio.to_thread(embed, text, is_query=is_query)


async def embed_batch_async(texts: list[str], *, is_query: bool = False) -> list[list[float]]:
    """Non-blocking wrapper for embed_batch() -- see embed_async()."""
    return await asyncio.to_thread(embed_batch, texts, is_query=is_query)
