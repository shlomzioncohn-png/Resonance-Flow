"""
Real embedding generation (BAAI/bge-small-en-v1.5, 384-dim, CPU-only).

Asymmetric model: queries need `is_query=True` (keyword-only) or
similarity scores get silently inflated. Prefer
`PgVectorStore.search_text()` over `search(embed(...))` for queries.

Long text truncates at 512 tokens, no chunking. Empty/whitespace text
raises ValueError -- nothing meaningful to embed.

`embed()`/`embed_batch()` block (CPU inference) -- use the `_async`
wrappers from async code.
"""

import asyncio
import threading
from pathlib import Path

from sentence_transformers import SentenceTransformer

from mind.config import EmbeddingModelSettings

EMBEDDING_DIM = 384
MAX_SEQ_LENGTH = 512

BGE_QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: "

_embedding_model_settings = EmbeddingModelSettings()
EMBEDDING_MODEL_NAME = _embedding_model_settings.name
# None = download from HF Hub (no local MODEL_CACHE_DIR set).
EMBEDDING_MODEL_LOCAL_DIR: Path | None = (
    Path(_embedding_model_settings.model_cache_dir) / EMBEDDING_MODEL_NAME.rsplit("/", 1)[-1]
    if _embedding_model_settings.model_cache_dir
    else None
)

_model: SentenceTransformer | None = None
_model_lock = threading.Lock()


def _get_model() -> SentenceTransformer:
    # threading.Lock, not asyncio.Lock -- runs on worker threads.
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


def task_text(title: str, description: str) -> str:
    """Canonical title+description join for embedding a task -- every
    producer (#470, #245, #471) must use this, or their vectors aren't
    comparable to each other."""
    return f"{title} {description}".strip()


def embed(text: str, *, is_query: bool = False) -> list[float]:
    """Deterministic embedding vector. is_query=True for search queries."""
    if not text.strip():
        raise ValueError(f"embed() got text with no extractable content: {text!r}")
    if is_query:
        text = BGE_QUERY_INSTRUCTION + text
    vector = _get_model().encode(text, normalize_embeddings=True)
    return vector.tolist()


def embed_batch(texts: list[str], *, is_query: bool = False) -> list[list[float]]:
    """Batch version of embed()."""
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
    """Non-blocking embed() for async callers."""
    return await asyncio.to_thread(embed, text, is_query=is_query)


async def embed_batch_async(texts: list[str], *, is_query: bool = False) -> list[list[float]]:
    """Non-blocking embed_batch() for async callers."""
    return await asyncio.to_thread(embed_batch, texts, is_query=is_query)


def similarity(a: str, b: str) -> float:
    """How alike two sentences are, 0..1."""
    vec_a = embed(a)
    vec_b = embed(b)
    cosine = sum(x * y for x, y in zip(vec_a, vec_b))  # embed() normalizes, so dot product = cosine
    return max(0.0, min(1.0, cosine))
