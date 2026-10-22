"""#920: the score publisher runs inside the API. It starts only when Kafka is
configured, and the API's lifespan starts and stops it."""

import threading
import time
from types import SimpleNamespace
from typing import ClassVar

import pytest
from fastapi.testclient import TestClient

import mind.api.main as api_main
from mind.config_file import ConfigFileError
from mind.messaging.config import MessagingSettings
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring import score_publisher_runner as runner
from mind.scoring.score_publisher import ScorePublisher
from mind.scoring.score_publisher_runner import start_score_publisher


def test_without_kafka_settings_it_does_not_start():
    # Locally and in tests: no config file, so nothing to connect to.
    assert start_score_publisher(MessagingSettings()) is None


@pytest.mark.parametrize(
    "settings",
    [
        MessagingSettings(bootstrap_servers="broker:9092"),
        MessagingSettings(schema_registry_url="http://sr:8080"),
    ],
)
def test_only_one_kafka_key_is_a_broken_config_not_kafka_off(settings):
    # #481: fail loudly, naming the missing key, rather than not start.
    with pytest.raises(ConfigFileError, match="incomplete Kafka config"):
        start_score_publisher(settings)


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
    monkeypatch.setattr(
        api_main, "get_settings", lambda: SimpleNamespace(vector_store=None, log_level="INFO")
    )
    monkeypatch.setattr(api_main, "start_score_publisher", start)


def test_the_api_starts_without_kafka_and_serves_health(monkeypatch, tmp_path):
    # The real start_score_publisher, on a config file with no kafka section.
    (tmp_path / "config.yaml").write_text("service: {log_level: INFO}\n", encoding="utf-8")
    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "config.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "nope.yaml"))
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


# --- with Kafka configured: the three threads start --------------------------

KAFKA = MessagingSettings(bootstrap_servers="broker:9092", schema_registry_url="http://sr:8080")


class _FakeProducer:
    def __init__(self, settings: MessagingSettings) -> None:
        self.settings = settings


class _FakeConsumer:
    """Records how it was built, hands one event to its handler, then waits for stop."""

    built: ClassVar[list["_FakeConsumer"]] = []

    def __init__(self, group_id: str, topics: list[str], settings: MessagingSettings) -> None:
        self.group_id = group_id
        self.topics = topics
        self.settings = settings
        self.message_type: type | None = None
        self.ended = threading.Event()
        _FakeConsumer.built.append(self)

    def run(self, message_type, handler, *, idle_timeout=None, stop) -> int:
        self.message_type = message_type
        handler(f"event from {self.topics[0]}", None)
        stop.wait()
        self.ended.set()
        return 1


@pytest.fixture
def started(monkeypatch):
    """start_score_publisher with Kafka configured, on fakes for the producer and
    consumers. Each handler records which topic's event reached it."""
    _FakeConsumer.built = []
    handled: dict[str, object] = {}
    monkeypatch.setattr(runner, "ProtobufProducer", _FakeProducer)
    monkeypatch.setattr(runner, "ProtobufConsumer", _FakeConsumer)
    monkeypatch.setattr(
        ScorePublisher, "handle_task_created", lambda self, e: handled.update(created=e)
    )
    monkeypatch.setattr(
        ScorePublisher, "handle_task_updated", lambda self, e: handled.update(updated=e)
    )

    running = start_score_publisher(KAFKA)
    assert running is not None
    yield running, handled
    running.stop()


def _wait_until(condition, seconds: float = 2.0) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(0.01)
    return condition()


def test_with_kafka_the_three_threads_start(started):
    running, _ = started

    assert running.threads.names == [
        "score-publisher-task.created",
        "score-publisher-task.updated",
        "score-publisher-rescore",
    ]
    assert running.healthy


def test_each_topic_reaches_its_own_handler(started):
    _, handled = started

    assert _wait_until(lambda: len(handled) == 2)
    assert handled == {
        "created": "event from task.created",
        "updated": "event from task.updated",
    }
    by_topic = {c.topics[0]: c for c in _FakeConsumer.built}
    assert by_topic["task.created"].message_type is TaskCreatedEvent
    assert by_topic["task.updated"].message_type is TaskUpdatedEvent
    assert {c.group_id for c in _FakeConsumer.built} == {"mind-score-publisher"}


def test_the_consumers_get_the_same_kafka_settings(started):
    _ = started

    assert _wait_until(lambda: len(_FakeConsumer.built) == 2)
    assert all(c.settings is KAFKA for c in _FakeConsumer.built)


def test_stop_halts_every_thread(started):
    running, _ = started
    assert _wait_until(lambda: len(_FakeConsumer.built) == 2)

    running.stop()

    assert all(c.ended.is_set() for c in _FakeConsumer.built)
    assert not running.healthy
