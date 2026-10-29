"""#482: /health stays truthful while the service is busy.

Relay's chart points startup, liveness and readiness all at /health, with
kubelet's default 1 s timeout: a /health that waits behind other work fails
the liveness probe and gets a working pod restarted. So what is checked here
is that busy-ness never reaches it -- slow requests in flight, a long
synchronous score in a publisher thread -- and, as the contract stands today,
what /health does not report.

benchmarks/serving-reliability-482.md measures the same against a real
API process and database (benchmarks/serving_reliability.py api-load).
"""

import asyncio
import threading
import time
from collections.abc import Iterator
from unittest.mock import AsyncMock, MagicMock
from uuid import UUID

import httpx
import pytest

from mind.api.main import app
from mind.api.v1.router import get_vector_index

TENANT = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
#: kubelet's default probe timeout, which Relay's chart does not override.
PROBE_TIMEOUT_S = 1.0


@pytest.fixture
def ready_app() -> Iterator[None]:
    """The app as after a successful start-up, without the real lifespan
    (no database, no Kafka): ASGITransport does not run it."""
    had = hasattr(app.state, "ready")
    previous = getattr(app.state, "ready", None)
    app.state.ready = True
    app.state.score_publisher = None
    yield
    if had:
        app.state.ready = previous
    else:
        del app.state.ready


@pytest.fixture
def slow_index() -> Iterator[MagicMock]:
    """A vector store whose every lookup takes 0.5 s, as under a loaded database."""

    async def slow_tenant_of(task_id: str) -> UUID:
        await asyncio.sleep(0.5)
        return TENANT

    index = MagicMock()
    index.tenant_of = AsyncMock(side_effect=slow_tenant_of)
    store = AsyncMock()
    store.get_vector.return_value = None
    index.for_tenant.return_value = store
    app.dependency_overrides[get_vector_index] = lambda: index
    yield index
    app.dependency_overrides.pop(get_vector_index, None)


def _client() -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://mind")


async def test_health_answers_at_once_while_slow_requests_are_in_flight(
    ready_app: None, slow_index: MagicMock
):
    async with _client() as client:
        in_flight = [
            asyncio.create_task(client.get(f"/v1/tasks/task-{i}/related")) for i in range(50)
        ]
        await asyncio.sleep(0.05)  # all 50 are waiting on the store now

        started = time.perf_counter()
        health = await client.get("/health")
        took = time.perf_counter() - started

        assert health.status_code == 200
        assert health.json()["status"] == "ok"
        # Not behind the 0.5 s lookups: well inside a probe's timeout.
        assert took < 0.25
        assert not any(task.done() for task in in_flight)
        responses = await asyncio.gather(*in_flight)
    assert {r.status_code for r in responses} == {200}


async def test_health_answers_while_a_publisher_thread_scores_for_a_long_time(ready_app: None):
    # The score publisher's threads score synchronously, in pure Python; one
    # holding the CPU for a second must not hold /health with it.
    stop = threading.Event()

    def long_score() -> None:
        while not stop.is_set():
            sum(i * i for i in range(10_000))

    busy = threading.Thread(target=long_score, daemon=True)
    busy.start()
    try:
        async with _client() as client:
            took = []
            for _ in range(5):
                started = time.perf_counter()
                health = await client.get("/health")
                took.append(time.perf_counter() - started)
                assert health.status_code == 200
    finally:
        stop.set()
        busy.join()
    assert max(took) < PROBE_TIMEOUT_S / 2


async def test_health_never_touches_the_vector_store(ready_app: None):
    # The liveness probe: a database blip must not restart every Mind pod.
    index = MagicMock()
    index.tenant_of = AsyncMock(side_effect=RuntimeError("database down"))
    app.dependency_overrides[get_vector_index] = lambda: index
    try:
        async with _client() as client:
            health = await client.get("/health")
    finally:
        app.dependency_overrides.pop(get_vector_index, None)
    assert health.status_code == 200
    index.tenant_of.assert_not_called()


async def test_health_does_not_report_the_score_publisher_today(ready_app: None):
    # The contract as it stands (#481, #920), pinned so a change to it is a
    # decision, not an accident: /health is start-up readiness only. A
    # publisher thread that died, or one stuck retrying, is not in it --
    # `RunningScorePublisher.healthy` / `.retrying` exist, but nothing reads
    # them here. Reporting them would need /health split into liveness and
    # readiness with Relay first: on the one shared path, a failing publisher
    # would get the pod restarted, not just taken out of rotation.
    app.state.score_publisher = MagicMock(healthy=False, retrying=["score-publisher-task.created"])
    async with _client() as client:
        health = await client.get("/health")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"


async def test_health_is_not_ready_before_start_up_or_after_shutdown_begins(ready_app: None):
    app.state.ready = False
    async with _client() as client:
        health = await client.get("/health")
    assert health.status_code == 503
    assert health.json()["status"] == "not_ready"
