"""#920: the score publisher runs inside the API. It starts only when Kafka is
configured, and the API's lifespan starts and stops it."""

from types import SimpleNamespace

from fastapi.testclient import TestClient

import mind.api.main as api_main
from mind.messaging.config import MessagingSettings
from mind.scoring.score_publisher_runner import kafka_configured, start_score_publisher


def test_without_kafka_settings_it_does_not_start():
    # Locally and in tests: no config file, so nothing to connect to.
    assert start_score_publisher(MessagingSettings()) is None


def test_kafka_needs_both_the_brokers_and_the_registry():
    assert not kafka_configured(MessagingSettings(bootstrap_servers="broker:9092"))
    assert not kafka_configured(MessagingSettings(schema_registry_url="http://sr:8080"))
    assert kafka_configured(
        MessagingSettings(bootstrap_servers="broker:9092", schema_registry_url="http://sr:8080")
    )


class _FakeStore:
    """Stands in for PgVectorStore, so the lifespan runs without Postgres."""

    def __init__(self, settings: object) -> None:
        pass

    async def open(self) -> None:
        pass

    async def close(self) -> None:
        pass


class _FakeRunning:
    def __init__(self) -> None:
        self.stopped = False
        self.healthy = True

    def stop(self) -> None:
        self.stopped = True


def _isolate_lifespan(monkeypatch, start) -> None:
    monkeypatch.setattr(api_main, "PgVectorStore", _FakeStore)
    monkeypatch.setattr(api_main, "get_settings", lambda: SimpleNamespace(vector_store=None))
    monkeypatch.setattr(api_main, "start_score_publisher", start)


def test_the_api_starts_without_kafka_and_serves_health(monkeypatch, tmp_path):
    # The real start_score_publisher, with no Kafka config file to read.
    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "nope.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "nope2.yaml"))
    _isolate_lifespan(monkeypatch, start_score_publisher)

    with TestClient(api_main.app) as client:
        assert api_main.app.state.score_publisher is None
        assert client.get("/health").status_code == 200


def test_the_api_stops_the_publisher_on_shutdown(monkeypatch):
    running = _FakeRunning()
    _isolate_lifespan(monkeypatch, lambda: running)

    with TestClient(api_main.app):
        assert api_main.app.state.score_publisher is running
        assert not running.stopped

    assert running.stopped
