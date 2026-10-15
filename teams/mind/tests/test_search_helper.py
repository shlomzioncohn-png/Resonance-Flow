"""nearest_tasks() -- the shared embed()+search() primitive behind #471/#245.
Unit-tested against a mock VectorStore (no real DB needed; search()'s own
pgvector behavior is covered by test_vector_store.py)."""

from unittest.mock import AsyncMock, patch

import pytest

from mind.embeddings.base import SearchResult
from mind.embeddings.embeddings import EMBEDDING_DIM
from mind.embeddings.search import nearest_tasks


async def test_nearest_tasks_embeds_then_searches_with_the_given_k():
    store = AsyncMock()
    store.search.return_value = [SearchResult(task_id="task-1", similarity=0.95)]

    results = await nearest_tasks(store, "Fix the login bug", k=3)

    assert results == [SearchResult(task_id="task-1", similarity=0.95)]
    store.search.assert_awaited_once()
    (vector,), kwargs = store.search.await_args.args, store.search.await_args.kwargs
    assert len(vector) == EMBEDDING_DIM
    assert kwargs["k"] == 3


async def test_nearest_tasks_propagates_embeds_value_error_on_empty_text():
    store = AsyncMock()
    with pytest.raises(ValueError):
        await nearest_tasks(store, "", k=1)
    store.search.assert_not_awaited()


async def test_nearest_tasks_defaults_to_is_query_false():
    """#471's symmetric comparison -- the default."""
    store = AsyncMock()
    with patch("mind.embeddings.search.embed_async") as mock_embed_async:
        mock_embed_async.return_value = [0.0] * EMBEDDING_DIM
        await nearest_tasks(store, "Fix the login bug", k=3)
    mock_embed_async.assert_awaited_once_with("Fix the login bug", is_query=False)


async def test_nearest_tasks_passes_through_is_query_true():
    """#245's asymmetric mention-vs-passage case."""
    store = AsyncMock()
    with patch("mind.embeddings.search.embed_async") as mock_embed_async:
        mock_embed_async.return_value = [0.0] * EMBEDDING_DIM
        await nearest_tasks(store, "the DB upgrade", k=3, is_query=True)
    mock_embed_async.assert_awaited_once_with("the DB upgrade", is_query=True)
