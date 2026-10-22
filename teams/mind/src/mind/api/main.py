"""MIND's outward-facing API -- the only door other teams use to talk to
MIND (health check today; search and scoring once Phase 2 lands).

See docs/API.md (repo root of this component) for the full contract:
endpoints, the standard error format, and the API versioning plan. See
src/mind/contracts/errors.py for the error shape every endpoint here
returns.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from starlette.middleware.base import RequestResponseEndpoint
from starlette.responses import Response

from mind import __version__
from mind.api.debug import router as debug_router
from mind.api.instrumentation import instrumented_call
from mind.api.v1 import router as v1_router
from mind.config import get_settings
from mind.contracts.errors import register_error_handlers
from mind.contracts.health import HealthResponse
from mind.embeddings.pgvector_store import PgVectorStore
from mind.observability.tracking import monitoring_settings
from mind.scoring.score_publisher_runner import RunningScorePublisher, start_score_publisher


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Open the vector store's connection pool once, on the app's own
    event loop, and close it on shutdown -- regardless of how shutdown
    happens (an unhandled exception in a route must not leak the pool).

    Marks the app ready (`app.state.ready`, read by /health) only once all
    of start-up has succeeded, and not ready again as shutdown begins.

    Also starts the score publisher (#920) in background threads when Kafka
    is configured, and stops them on shutdown.
    """
    # First, so a missing or broken config stops start-up before anything
    # else runs (#481); then apply its service.log_level, which the
    # import-time basicConfig below can't know yet.
    settings = get_settings()
    logging.getLogger().setLevel(settings.log_level)
    # Read the monitoring limits now, so an invalid MIND_MONITORING__* value
    # is logged at start-up rather than at the first model call. It never
    # stops the app: the defaults are used instead (see monitoring_settings).
    monitoring_settings()
    store = PgVectorStore(settings.vector_store)
    score_publisher: RunningScorePublisher | None = None
    # Set before anything can fail, so a reader (#481's /health/live) never
    # finds it missing: None means not running.
    app.state.score_publisher = None
    try:
        await store.open()
        app.state.vector_store = store
        # Scores Inbox's tasks and publishes them to Chain. None when Kafka is
        # not configured (locally, in tests).
        score_publisher = start_score_publisher()
        app.state.score_publisher = score_publisher
        app.state.ready = True
        yield
    finally:
        app.state.ready = False
        if score_publisher is not None:
            # In a worker thread: stop() waits a few seconds for the threads,
            # which must not block the event loop's other shutdown work.
            await asyncio.to_thread(score_publisher.stop)
            app.state.score_publisher = None
        await store.close()


# Configure logging once, centrally, here -- before anything else in the
# app logs a thing. Python's logging module does nothing by default: with
# no handler configured anywhere, .info() calls are silently dropped, and
# .error()/.exception() calls only reach stderr via logging.lastResort,
# unformatted (no timestamp, level, or logger name). uvicorn only
# configures its own "uvicorn"/"uvicorn.access"/"uvicorn.error" loggers,
# not the root logger, so it doesn't do this for us. With this in place,
# every module can just do `logging.getLogger(__name__)` and call
# .info()/.error() normally -- no per-module handler setup needed.
# INFO only until lifespan() applies the configured service.log_level.
logging.basicConfig(level=logging.INFO, format="%(levelname)s:%(name)s:%(message)s")

app = FastAPI(title="MIND API", version=__version__, lifespan=lifespan)

register_error_handlers(app)

# /v1 business routes (search/score once Phase 2 lands) -- see
# mind.api.v1 and docs/API.md "API versioning plan". /health below
# stays outside it deliberately (unversioned).
app.include_router(v1_router)

# /debug/simulate-call, /debug/instrumentation and /debug/model-calls --
# see api/debug.py and api/README.md "Instrumentation".
app.include_router(debug_router)

_START_TIME = time.time()


@app.middleware("http")
async def _add_request_id(request: Request, call_next: RequestResponseEndpoint) -> Response:
    """Attach a request id to every request/response so an error's
    request_id (see contracts/errors.py) can be correlated with logs.

    request: the incoming HTTP request.
    call_next: continues the middleware/handler chain for this request
        and returns its eventual Response -- calling and awaiting it is
        what actually runs the rest of the app for this request.
    """
    request_id = request.headers.get("x-request-id")
    if request_id is None:
        request_id = str(uuid.uuid4())
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers["x-request-id"] = request_id
    return response


@app.get(
    "/health",
    tags=["health"],
    response_model=HealthResponse,
    responses={
        503: {
            "model": HealthResponse,
            "description": "Start-up hasn't completed successfully, or shutdown has begun.",
        }
    },
)
async def health(request: Request, response: Response) -> HealthResponse:
    """Liveness/readiness check for Relay to monitor (#481).

    200 only once start-up has completed successfully: the config loaded and
    the vector store opened and migrated (see lifespan). 503 otherwise. A
    start-up that fails outright stops the process instead, so in practice
    Relay sees no answer at all then, not a 503.

    Deliberately cheap: never touches the vector store, a model, or any
    other downstream dependency per request. The same path is Relay's
    liveness probe, so a check here that failed on a database blip would
    restart every MIND pod. See docs/API.md for the response shape and
    status codes.

    Wrapped in instrumented_call as a trivial end-to-end demonstration
    of the wrapper -- not because a health check is itself a model
    call. See api/instrumentation.py.
    """
    with instrumented_call("health_check"):
        ready = getattr(request.app.state, "ready", False)
        if not ready:
            response.status_code = 503
        return HealthResponse(
            status="ok" if ready else "not_ready",
            version=__version__,
            uptime_seconds=round(time.time() - _START_TIME, 3),
        )
