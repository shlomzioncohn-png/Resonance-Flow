"""Tests for GET /v1/tasks/{task_id}/related -- find_related() itself is
tested against a real store in test_related_tasks.py; this covers only
the HTTP wiring, with a mocked store so the real lifespan never runs."""

from unittest.mock import AsyncMock, MagicMock
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from mind.api.main import app
from mind.api.v1.router import get_vector_index
from mind.config import get_settings
from mind.embeddings.base import SearchResult
from mind.embeddings.confidence import confidence_from_similarity

client = TestClient(app)

TENANT = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


@pytest.fixture
def mock_index():
    """Depends()-based override, not app.state mutation -- app is a
    shared module-level object other test files also import."""
    index = MagicMock()
    index.tenant_of = AsyncMock(return_value=TENANT)
    index.for_tenant.return_value = AsyncMock()
    app.dependency_overrides[get_vector_index] = lambda: index
    yield index
    app.dependency_overrides.pop(get_vector_index, None)


@pytest.fixture
def mock_store(mock_index):
    return mock_index.for_tenant.return_value


def test_returns_200_with_the_expected_shape(mock_store):
    mock_store.get_vector.return_value = [0.0] * 4
    mock_store.search.return_value = [SearchResult(task_id="task-2", similarity=0.9)]

    response = client.get("/v1/tasks/task-1/related")

    assert response.status_code == 200
    expected_confidence = confidence_from_similarity(0.9, get_settings().related_task_threshold)
    assert response.json() == {
        "related": [
            {"task_id": "task-2", "similarity": 0.9, "confidence": expected_confidence}
        ]
    }


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


def test_searches_only_inside_the_tenant_of_the_task(mock_index, mock_store):
    """#841: the tenant comes from the task's own row, not from the caller."""
    mock_store.get_vector.return_value = None

    client.get("/v1/tasks/task-1/related")

    mock_index.tenant_of.assert_awaited_once_with("task-1")
    mock_index.for_tenant.assert_called_once_with(TENANT)


def test_returns_an_empty_list_for_a_task_with_no_tenant(mock_index):
    mock_index.tenant_of.return_value = None

    response = client.get("/v1/tasks/task-old/related")

    assert response.status_code == 200
    assert response.json() == {"related": []}
    mock_index.for_tenant.assert_not_called()
