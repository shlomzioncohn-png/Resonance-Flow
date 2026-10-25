"""Tests for MIND's API -- /health (incl. its readiness, #481) and the
standard error format.

These exercise the same app object the Dockerfile serves
(`mind.api.main:app`), so a green run here is a strong signal `docker
compose up` will behave the same way.
"""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from mind.api import main
from mind.api.main import app
from mind.config import get_settings

# No `with`: the lifespan never runs, so this client sees an app that has not
# started up -- what /health must report as not ready.
client = TestClient(app)


class _FakeStore:
    """Stands in for PgVectorStore: start-up is under test here, not a database."""

    opened = 0

    def __init__(self, settings: object) -> None:
        pass

    async def open(self) -> None:
        type(self).opened += 1

    async def close(self) -> None:
        pass


class _FailingStore(_FakeStore):
    async def open(self) -> None:
        raise RuntimeError("Vector store table 'task_embeddings' does not exist")


@pytest.fixture(autouse=True)
def _not_started(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """Each test starts from an app that never started, with a fake store."""

    monkeypatch.delattr(app.state, "ready", raising=False)
    monkeypatch.setattr(main, "PgVectorStore", _FakeStore)
    _FakeStore.opened = 0
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_health_returns_200_with_expected_shape_once_started():
    with TestClient(app) as started:
        response = started.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert isinstance(body["version"], str)
    assert isinstance(body["uptime_seconds"], (int, float))


def test_health_is_503_not_ready_before_start_up():
    response = client.get("/health")

    assert response.status_code == 503
    assert response.json()["status"] == "not_ready"


def test_health_is_503_again_once_shutdown_has_begun():
    with TestClient(app) as started:
        assert started.get("/health").status_code == 200

    assert client.get("/health").status_code == 503


def test_failed_start_up_never_reports_ready(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(main, "PgVectorStore", _FailingStore)

    with pytest.raises(RuntimeError, match="does not exist"), TestClient(app):
        pass

    assert client.get("/health").status_code == 503


def test_health_does_not_touch_the_store_per_request(monkeypatch: pytest.MonkeyPatch):
    """Same path as the liveness probe: a DB blip must not fail it."""

    with TestClient(app) as started:
        monkeypatch.setattr(app.state, "vector_store", object())  # any use would raise
        responses = [started.get("/health") for _ in range(3)]

    assert [r.status_code for r in responses] == [200, 200, 200]
    assert _FakeStore.opened == 1  # once, at start-up


def test_openapi_documents_the_503():
    responses = app.openapi()["paths"]["/health"]["get"]["responses"]

    assert {"200", "503"} <= responses.keys()


def test_health_response_has_a_request_id_header():
    response = client.get("/health")
    assert "x-request-id" in response.headers


def test_unknown_route_returns_standard_error_format():
    response = client.get("/does-not-exist")
    assert response.status_code == 404

    body = response.json()
    assert body["error_code"] == "not_found"
    assert isinstance(body["message"], str)
    assert isinstance(body["request_id"], str)
    assert isinstance(body["timestamp"], (int, float))


def test_caller_supplied_request_id_is_echoed_back():
    response = client.get("/does-not-exist", headers={"x-request-id": "my-test-id"})
    assert response.headers["x-request-id"] == "my-test-id"
    assert response.json()["request_id"] == "my-test-id"


def test_metrics_exposes_http_request_metrics():
    client.get("/health")
    response = client.get("/metrics")
    assert response.status_code == 200
    assert 'http_requests_total{handler="/health"' in response.text
