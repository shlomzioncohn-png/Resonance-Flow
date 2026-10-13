"""nearest_tasks() -- the shared embed()+search() primitive behind #471/#245.
Unit-tested against a mock VectorStore (no real DB needed; search()'s own
pgvector behavior is covered by test_vector_store.py)."""

from unittest.mock import AsyncMock

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
