"""Response contract for GET /health.

Same reasoning as ErrorResponse in errors.py: a response crossing the
API boundary to an external client (Relay) should be a defined Pydantic
model, not a raw dict -- FastAPI then actually validates the response
against it, and /docs reflects the real shape. See docs/API.md for the
field reference and status-code table.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    """The body of GET /health."""

    status: Literal["ok", "not_ready"] = Field(
        ...,
        description=(
            '"ok" (HTTP 200) once start-up has completed successfully; '
            '"not_ready" (HTTP 503) before that or once shutdown has begun.'
        ),
    )
    version: str = Field(
        ..., description="MIND's package version (mind.__version__)."
    )
    uptime_seconds: float = Field(
        ..., description="Seconds since the process started."
    )
