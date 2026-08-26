"""Standard error response contract for MIND's API.

Every error returned by any MIND endpoint -- present or future -- uses this
shape. See docs/API.md (repo root of this component) for the full field
reference and status-code table. Call register_error_handlers(app) once,
right after the FastAPI app is constructed (see api/main.py); every route
added later gets the standard shape for free, with no per-endpoint work.
"""

from __future__ import annotations

import logging
import time
import uuid
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

# Register against Starlette's base HTTPException, not fastapi.HTTPException.
# FastAPI's own HTTPException is a subclass used by path-operation code, but
# 404s for routes that don't match anything are raised by Starlette's router
# as the *base* class -- registering only the subclass would silently miss
# those and let them fall through to FastAPI's default (non-standard) 404
# body. Registering the base class catches both, since Starlette walks the
# raised exception's MRO to find a handler.
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("mind.errors")
# uvicorn only attaches handlers to its own loggers, and the root logger
# has no handler by default -- without this, every unhandled-exception
# log below is silently dropped instead of reaching stderr. Scoped to
# this logger only (propagate=False) so it neither depends on nor
# disturbs whatever logging setup the rest of the app ends up using.
if not logger.handlers:
    _handler = logging.StreamHandler()
    _handler.setFormatter(logging.Formatter("%(levelname)s:%(name)s:%(message)s"))
    logger.addHandler(_handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False

# error_code -> default HTTP status code it corresponds to.
# Keep this in sync with the table in docs/API.md.
_STATUS_TO_CODE = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    409: "conflict",
    422: "validation_error",
    429: "rate_limited",
    500: "internal_error",
    503: "service_unavailable",
}


class ErrorResponse(BaseModel):
    """The body of every non-2xx response from MIND's API."""

    error_code: str = Field(
        ...,
        description=(
            "Stable, machine-readable identifier, e.g. 'not_found', "
            "'validation_error', 'internal_error'. Safe to branch on in "
            "calling code."
        ),
    )
    message: str = Field(
        ...,
        description=(
            "Human-readable summary of what went wrong. Safe to log or "
            "show to a caller; never includes secrets, stack traces, or "
            "internal paths."
        ),
    )
    details: dict[str, Any] | None = Field(
        default=None,
        description=(
            "Optional structured context (e.g. which field failed "
            "validation). Omitted when there is nothing extra to add."
        ),
    )
    request_id: str = Field(
        ...,
        description=(
            "Correlates this error with MIND's server-side logs. Echoes "
            "the caller's X-Request-Id header if one was sent, otherwise "
            "a generated UUID."
        ),
    )
    timestamp: float = Field(
        default_factory=time.time,
        description="Unix timestamp (seconds) when the error was produced.",
    )


def _code_for_status(status_code: int) -> str:
    return _STATUS_TO_CODE.get(status_code, "error")


def _request_id(request: Request) -> str:
    """The request id to put on an error response.

    Normally already set by main.py's request-id middleware for every
    request; the fallback only matters if a handler below somehow runs
    without that middleware (e.g. in an isolated test).
    """
    return getattr(request.state, "request_id", str(uuid.uuid4()))


def _error_body(
    error_code: str,
    message: str,
    request_id: str,
    details: dict[str, Any] | None = None,
) -> dict:
    return ErrorResponse(
        error_code=error_code,
        message=message,
        details=details,
        request_id=request_id,
    ).model_dump()


def register_error_handlers(app: FastAPI) -> None:
    """Wire up MIND's standard error format as FastAPI exception handlers."""

    @app.exception_handler(StarletteHTTPException)
    async def _http_exception_handler(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        request_id = _request_id(request)
        message = exc.detail if isinstance(exc.detail, str) else "Request failed."
        return JSONResponse(
            status_code=exc.status_code,
            content=_error_body(
                error_code=_code_for_status(exc.status_code),
                message=message,
                request_id=request_id,
            ),
        )

    @app.exception_handler(RequestValidationError)
    async def _validation_exception_handler(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        request_id = _request_id(request)
        return JSONResponse(
            status_code=422,
            content=_error_body(
                error_code="validation_error",
                message="Request failed validation.",
                request_id=request_id,
                details={"errors": exc.errors()},
            ),
        )

    @app.exception_handler(Exception)
    async def _unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        request_id = _request_id(request)
        # The response body deliberately stays generic (never leaks the
        # exception message, type, or a traceback to the caller) -- but
        # the real cause still has to land *somewhere*, or request_id
        # has nothing to correlate against. Log it server-side, tagged
        # with the same request_id, with the full traceback.
        logger.error(
            "unhandled_exception request_id=%s path=%s", request_id, request.url.path, exc_info=exc
        )
        return JSONResponse(
            status_code=500,
            content=_error_body(
                error_code="internal_error",
                message="Something went wrong on MIND's side.",
                request_id=request_id,
            ),
        )
