"""
Tests for mind.embeddings.embeddings -- no DB required (embed()/embed_batch()
never touch pgvector).
"""

import asyncio
from unittest.mock import patch

from mind.embeddings import embeddings as embeddings_module
from mind.embeddings.embeddings import EMBEDDING_DIM, embed, embed_async, embed_batch


def test_embed_is_deterministic():
    text = "TO: Attorney General Betty D. Montgomery, fax cover sheet"
    assert embed(text) == embed(text)


def test_embed_different_text_gives_different_vector():
    assert embed("Fax cover sheet from the Attorney General's office") != embed(
        "Completely unrelated memo about office snacks"
    )


def test_embed_returns_correct_shape():
    vec = embed("Some record text")
    assert isinstance(vec, list)
    assert len(vec) == EMBEDDING_DIM
    assert all(isinstance(x, float) for x in vec)


def test_embed_long_text_does_not_crash():
    # Far beyond max_seq_length -- must truncate silently, not raise.
    long_text = " ".join(["invoice payment vendor purchase order"] * 1000)
    vec = embed(long_text)
    assert len(vec) == EMBEDDING_DIM


def test_embed_long_text_is_still_deterministic():
    long_text = "field data " * 2000
    assert embed(long_text) == embed(long_text)


def test_embed_is_query_changes_the_vector():
    text = "customer pricing proposal"
    assert embed(text, is_query=False) != embed(text, is_query=True)


def test_embed_is_query_still_deterministic():
    text = "customer pricing proposal"
    assert embed(text, is_query=True) == embed(text, is_query=True)


def test_embed_batch_empty_list_returns_empty_list():
    assert embed_batch([]) == []


async def test_embed_async_concurrent_calls_load_model_only_once():
    """Concurrent calls on a cold model build exactly one instance."""
    original_model = embeddings_module._model
    embeddings_module._model = None
    construction_count = 0
    real_constructor = embeddings_module.SentenceTransformer

    def counting_constructor(*args, **kwargs):
        nonlocal construction_count
        construction_count += 1
        return real_constructor(*args, **kwargs)

    try:
        with patch.object(
            embeddings_module, "SentenceTransformer", side_effect=counting_constructor
        ):
            results = await asyncio.gather(
                *(embed_async(f"concurrent load test {i}") for i in range(8))
            )
    finally:
        embeddings_module._model = original_model

    assert construction_count == 1
    assert all(len(vec) == EMBEDDING_DIM for vec in results)


def test_embed_batch_matches_individual_embed():
    # Batch vs. individual can differ by float32 rounding noise.
    texts = [
        "Fax cover sheet from the Attorney General's office",
        "Progress report for Lorillard division",
    ]
    batch_vectors = embed_batch(texts)
    assert len(batch_vectors) == 2
    for text, vec in zip(texts, batch_vectors):
        assert len(vec) == EMBEDDING_DIM
        individual = embed(text)
        max_diff = max(abs(a - b) for a, b in zip(vec, individual))
        assert max_diff < 1e-5
