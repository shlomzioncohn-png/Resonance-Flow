"""
Real embedding generation (BAAI/bge-small-en-v1.5, 384-dim, CPU-only).

Asymmetric model: queries need `is_query=True` (keyword-only) or
similarity scores get silently inflated. Prefer
`PgVectorStore.search_text()` over `search(embed(...))` for queries.

Long text truncates at 512 tokens, no chunking. Empty/whitespace text
raises ValueError -- nothing meaningful to embed.

`embed()`/`embed_batch()` block (CPU inference) -- use the `_async`
wrappers from async code.

`sentence_transformers` (and torch under it, ~380 MB) is imported when the
model is first loaded, not when this module is (#482): every process that
imports `mind.embeddings` without ever embedding -- Mind's API above all, whose
/related reads stored vectors -- would otherwise carry it.
`embeddings.SentenceTransformer` still resolves, through the module's
`__getattr__`.
"""

from __future__ import annotations

import asyncio
import threading
import time
from pathlib import Path
from typing import TYPE_CHECKING, Any

from mind.config import EmbeddingModelSettings
from mind.observability.compute import CpuClock
from mind.observability.tracking import CallKind, CallStatus, record_call

if TYPE_CHECKING:
    from sentence_transformers import SentenceTransformer

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

# What record_call() files every encode under (#482): one row per model.
EMBEDDING_CALL_NAME = f"{EMBEDDING_MODEL_NAME.rsplit('/', 1)[-1]}-local"

_model: SentenceTransformer | None = None
_model_lock = threading.Lock()


def __getattr__(name: str) -> Any:
    """`embeddings.SentenceTransformer`, imported on first access (see the module docstring)."""
    if name == "SentenceTransformer":
        from sentence_transformers import SentenceTransformer

        return SentenceTransformer
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def _sentence_transformer() -> type[SentenceTransformer]:
    """The class to build the model with: one a test patched onto this module,
    else sentence_transformers', imported now."""
    patched: type[SentenceTransformer] | None = globals().get("SentenceTransformer")
    if patched is not None:
        return patched
    from sentence_transformers import SentenceTransformer

    return SentenceTransformer


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
                _model = _sentence_transformer()(model_source)
                _model.max_seq_length = MAX_SEQ_LENGTH
    return _model


def _encode(model: SentenceTransformer, texts: str | list[str]) -> Any:
    """model.encode(), recorded with record_call() (#482): wall time and CPU
    seconds, ERROR if it raised. The model is loaded before the clock starts,
    as ASR's window leaves Whisper's load out. A batch is one call. CPU is
    process-wide (observability/compute.py): torch spreads one encode over a
    thread pool, so this thread's own CPU would leave most of it out."""
    clock = CpuClock()
    started = time.perf_counter()
    status = CallStatus.ERROR
    try:
        vectors = model.encode(texts, normalize_embeddings=True)
        status = CallStatus.SUCCESS
        return vectors
    finally:
        record_call(
            EMBEDDING_CALL_NAME,
            (time.perf_counter() - started) * 1000,
            None,
            kind=CallKind.EMBEDDING,
            status=status,
            cpu_s=clock.elapsed_s(),
        )


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
    vector = _encode(_get_model(), text)
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
    vectors = _encode(_get_model(), texts)
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
