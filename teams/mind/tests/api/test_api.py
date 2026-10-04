"""Tests for MIND's API -- /health and the standard error format.

These exercise the same app object the Dockerfile serves
(`mind.api.main:app`), so a green run here is a strong signal `docker
compose up` will behave the same way.
"""

from fastapi.testclient import TestClient

from mind.api.main import app

client = TestClient(app)


def test_health_returns_200_with_expected_shape():
    response = client.get("/health")
    assert response.status_code == 200

    body = response.json()
    assert body["status"] == "ok"
    assert isinstance(body["version"], str)
    assert isinstance(body["uptime_seconds"], (int, float))


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
