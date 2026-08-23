"""MIND's outward-facing API -- the only door other teams use to talk to
MIND (health check today; search and scoring once Phase 2 lands).

See docs/API.md (repo root of this component) for the full contract:
endpoints, the standard error format, and the API versioning plan. See
src/mind/contracts/errors.py for the error shape every endpoint here
returns.
"""

from __future__ import annotations

import time
import uuid

from fastapi import FastAPI, Request

from mind import __version__
from mind.api.v1 import router as v1_router
from mind.contracts.errors import register_error_handlers

app = FastAPI(title="MIND API", version=__version__)

register_error_handlers(app)

# /v1 business routes (search/score once Phase 2 lands) -- see
# mind.api.v1 and docs/API.md "API versioning plan". /health below
# stays outside it deliberately (unversioned).
app.include_router(v1_router)

_START_TIME = time.time()


@app.middleware("http")
async def _add_request_id(request: Request, call_next):
    """Attach a request id to every request/response so an error's
    request_id (see contracts/errors.py) can be correlated with logs."""
    request.state.request_id = request.headers.get("x-request-id", str(uuid.uuid4()))
    response = await call_next(request)
    response.headers["x-request-id"] = request.state.request_id
    return response


@app.get("/health", tags=["health"])
async def health() -> dict:
    """Liveness/readiness check for Relay to monitor.

    Deliberately cheap: never touches the vector store, a model, or any
    other downstream dependency. A 200 here means "the MIND process is up
    and serving HTTP requests" -- nothing more. See docs/API.md for the
    documented response shape and status codes.
    """
    return {
        "status": "ok",
        "version": __version__,
        "uptime_seconds": round(time.time() - _START_TIME, 3),
    }
