"""MIND's outward-facing API -- the only door other teams use to talk to
MIND (health check today; search and scoring once Phase 2 lands).

See docs/API.md (repo root of this component) for the full contract:
endpoints, the standard error format, and the API versioning plan. See
src/mind/contracts/errors.py for the error shape every endpoint here
returns.
"""

from __future__ import annotations

import logging
import time
import uuid

from fastapi import FastAPI, Request
from starlette.middleware.base import RequestResponseEndpoint
from starlette.responses import Response

from mind import __version__
from mind.api.v1 import router as v1_router
from mind.contracts.errors import register_error_handlers
from mind.contracts.health import HealthResponse

# Configure logging once, centrally, here -- before anything else in the
# app logs a thing. Python's logging module does nothing by default: with
# no handler configured anywhere, .info() calls are silently dropped, and
# .error()/.exception() calls only reach stderr via logging.lastResort,
# unformatted (no timestamp, level, or logger name). uvicorn only
# configures its own "uvicorn"/"uvicorn.access"/"uvicorn.error" loggers,
# not the root logger, so it doesn't do this for us. With this in place,
# every module can just do `logging.getLogger(__name__)` and call
# .info()/.error() normally -- no per-module handler setup needed.
logging.basicConfig(level=logging.INFO, format="%(levelname)s:%(name)s:%(message)s")

app = FastAPI(title="MIND API", version=__version__)

register_error_handlers(app)

# /v1 business routes (search/score once Phase 2 lands) -- see
# mind.api.v1 and docs/API.md "API versioning plan". /health below
# stays outside it deliberately (unversioned).
app.include_router(v1_router)

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


@app.get("/health", tags=["health"], response_model=HealthResponse)
async def health() -> HealthResponse:
    """Liveness/readiness check for Relay to monitor.

    Deliberately cheap: never touches the vector store, a model, or any
    other downstream dependency. A 200 here means "the MIND process is up
    and serving HTTP requests" -- nothing more. See docs/API.md for the
    documented response shape and status codes.
    """
    return HealthResponse(
        status="ok",
        version=__version__,
        uptime_seconds=round(time.time() - _START_TIME, 3),
    )
