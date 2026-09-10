"""MIND's /v1 API surface -- versioned business endpoints (search, score,
... once Phase 2 lands). See docs/API.md "API versioning plan" for what
belongs here vs. what stays unversioned in api/main.py (e.g. /health).

Empty today -- add new business routes to `router` below with
`@router.get(...)` / `@router.post(...)`, not directly to the FastAPI
app in api/main.py. When /v2 is needed, add a sibling `mind.api.v2`
package the same way so both versions can run side by side.
"""

from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/v1")
