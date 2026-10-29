"""#920: the score publisher runs inside the API. It starts only when Kafka is
configured, and the API's lifespan starts and stops it."""

import logging
import threading
import time
from types import SimpleNamespace
from typing import ClassVar

import httpx
import pytest
from fastapi.testclient import TestClient

import mind.api.main as api_main
from mind.config_file import ConfigFileError
from mind.messaging.config import MessagingSettings
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.observability.context import CallContext, current_context, item_ref
from mind.observability.recording import RecordedScorer, RecordingClient
from mind.observability.tracking import CallKind, CallStatus, _reset_for_tests, get_recent_calls
from mind.scoring import score_publisher_runner as runner
from mind.scoring.chain_hierarchy_client import ChainHierarchySettings
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
    # The lifespan sets the root logger's level: put it back after the test.
    root = logging.getLogger()
    monkeypatch.setattr(root, "level", root.level)


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
    assert running.retrying == []


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
    # A group per topic, so one topic's retries never rebalance the other's.
    assert by_topic["task.created"].group_id == "mind-score-publisher-task-created"
    assert by_topic["task.updated"].group_id == "mind-score-publisher-task-updated"


def test_the_consumers_get_the_same_kafka_settings(started):
    _ = started

    assert _wait_until(lambda: len(_FakeConsumer.built) == 2)
    assert all(c.settings is KAFKA for c in _FakeConsumer.built)


def test_every_score_and_chain_request_is_recorded(started):
    # #482: the model and the Chain client are wrapped from the outside.
    running, _ = started
    publisher = running.publisher

    assert isinstance(publisher._model, RecordedScorer)  # what ScorePublisher scores with
    assert isinstance(publisher._children_client._client, RecordingClient)


def test_the_recorded_chain_client_keeps_its_settings_and_falls_back(monkeypatch):
    _reset_for_tests()
    settings = ChainHierarchySettings(base_url="http://chain.test", timeout_seconds=0.2)

    def chain_down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    client = runner._recorded_chain_client(settings, httpx.MockTransport(chain_down))

    assert client.get_ancestors("task-1") == []  # #474's fallback, unchanged
    [call] = get_recent_calls()
    assert (call.model_name, call.kind, call.status) == (
        "chain-ancestors",
        CallKind.CHAIN,
        CallStatus.ERROR,
    )
    http_client = client._client
    assert str(http_client.base_url) == "http://chain.test"
    assert http_client.timeout == httpx.Timeout(0.2)


def test_stop_halts_every_thread(started):
    running, _ = started
    assert _wait_until(lambda: len(_FakeConsumer.built) == 2)

    running.stop()

    assert all(c.ended.is_set() for c in _FakeConsumer.built)
    assert not running.healthy


# --- #482: each score attributed to its task and item ------------------------------------

_TENANT = "550e8400-e29b-41d4-a716-446655440000"
_TASK = "4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f"
_SOURCE_REF = "bridge:6f1c1f9e-0000-4000-8000-000000000001"


@pytest.mark.parametrize(
    ("message_type", "event"),
    [
        (
            TaskCreatedEvent,
            TaskCreatedEvent(id=_TASK, tenant_id=_TENANT, source_ref=_SOURCE_REF),
        ),
        (
            TaskUpdatedEvent,
            TaskUpdatedEvent(task_id=_TASK, tenant_id=_TENANT, source_ref=_SOURCE_REF),
        ),
    ],
)
def test_a_task_events_calls_are_attributed_to_its_task_and_item(monkeypatch, message_type, event):
    seen: list[CallContext] = []

    class Consumer:
        def __init__(self, **kwargs) -> None:
            pass

        def run(self, message_type, handler, *, idle_timeout=None, stop) -> int:
            handler(event, None)
            return 1

    monkeypatch.setattr(runner, "ProtobufConsumer", Consumer)
    target = runner._consume(
        KAFKA, "task.created", message_type, lambda e: seen.append(current_context())
    )

    target(threading.Event(), lambda: None)

    # The same item the landed runner named, from the same source_ref.
    assert seen == [CallContext(item=item_ref(_TENANT, _SOURCE_REF), task=_TASK, stage="score")]
    assert current_context() == CallContext()


def test_an_event_without_a_source_ref_is_still_scored_with_its_task_only():
    event = TaskCreatedEvent(id=_TASK, tenant_id=_TENANT)  # proto3: source_ref ""
    with runner._score_context(event) as context:
        assert context == CallContext(task=_TASK, stage="score")
