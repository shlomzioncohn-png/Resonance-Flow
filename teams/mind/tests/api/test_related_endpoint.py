"""Tests for GET /v1/tasks/{task_id}/related -- find_related() itself is
tested against a real store in test_related_tasks.py; this covers only
the HTTP wiring, with a mocked store so the real lifespan never runs."""

from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient

from mind.api.main import app
from mind.api.v1.router import get_vector_store
from mind.embeddings.base import SearchResult

client = TestClient(app)


@pytest.fixture
def mock_store():
    """Depends()-based override, not app.state mutation -- app is a
    shared module-level object other test files also import."""
    store = AsyncMock()
    app.dependency_overrides[get_vector_store] = lambda: store
    yield store
    app.dependency_overrides.pop(get_vector_store, None)


def test_returns_200_with_the_expected_shape(mock_store):
    mock_store.get_vector.return_value = [0.0] * 4
    mock_store.search.return_value = [SearchResult(task_id="task-2", similarity=0.9)]

    response = client.get("/v1/tasks/task-1/related")

    assert response.status_code == 200
    assert response.json() == {"related": [{"task_id": "task-2", "similarity": 0.9}]}


def test_returns_200_with_an_empty_list_for_an_unindexed_task(mock_store):
    mock_store.get_vector.return_value = None

    response = client.get("/v1/tasks/task-unknown/related")

    assert response.status_code == 200
    assert response.json() == {"related": []}


def test_call_is_recorded_through_instrumented_call(mock_store):
    """#450 requires every served call to go through instrumented_call(),
    proven by a test -- this checks the aggregate actually moves."""
    mock_store.get_vector.return_value = None
    before = client.get("/debug/instrumentation").json()["call_count"]

    client.get("/v1/tasks/task-1/related")

    after = client.get("/debug/instrumentation").json()["call_count"]
    assert after == before + 1


def test_an_unhandled_store_error_returns_the_standard_error_format(mock_store):
    # raise_server_exceptions=False: the default TestClient re-raises an
    # unhandled exception instead of returning the 500 the registered
    # handler (contracts/errors.py) would produce for a real caller --
    # this test wants to see that real response, not the raw traceback.
    mock_store.get_vector.side_effect = RuntimeError("db down")
    error_client = TestClient(app, raise_server_exceptions=False)

    response = error_client.get("/v1/tasks/task-1/related")

    assert response.status_code == 500
    body = response.json()
    assert body["error_code"] == "internal_error"
    assert "db down" not in body["message"]
