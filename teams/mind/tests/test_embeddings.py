"""
Tests for mind.embeddings.generate -- no DB required (embed()/embed_batch()
never touch pgvector).
"""

from mind.embeddings.config import EMBEDDING_DIM
from mind.embeddings.generate import embed, embed_batch


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


def test_embed_rejects_non_string():
    import pytest

    with pytest.raises(TypeError):
        embed(12345)


def test_embed_long_text_does_not_crash():
    # ~5000 words, far beyond the model's 512-token max_seq_length -- must
    # truncate silently, not raise.
    long_text = " ".join(["invoice payment vendor purchase order"] * 1000)
    vec = embed(long_text)
    assert len(vec) == EMBEDDING_DIM


def test_embed_long_text_is_still_deterministic():
    long_text = "field data " * 2000
    assert embed(long_text) == embed(long_text)


def test_embed_is_query_changes_the_vector():
    # BGE is asymmetric: the query instruction prefix should change the
    # embedding relative to encoding the same text as a passage.
    text = "customer pricing proposal"
    assert embed(text, is_query=False) != embed(text, is_query=True)


def test_embed_is_query_still_deterministic():
    text = "customer pricing proposal"
    assert embed(text, is_query=True) == embed(text, is_query=True)


def test_embed_batch_matches_individual_embed():
    # Batched vs. single-item inference can differ by float32 rounding noise
    # (padding/accumulation order) -- not exact equality, but should agree
    # closely.
    texts = ["Fax cover sheet from the Attorney General's office", "Progress report for Lorillard division"]
    batch_vectors = embed_batch(texts)
    assert len(batch_vectors) == 2
    for text, vec in zip(texts, batch_vectors):
        assert len(vec) == EMBEDDING_DIM
        individual = embed(text)
        max_diff = max(abs(a - b) for a, b in zip(vec, individual))
        assert max_diff < 1e-5
