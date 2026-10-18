"""MIND's /v1 API surface -- versioned business endpoints. See
docs/API.md "API versioning plan" for what belongs here vs. what stays
unversioned in api/main.py (e.g. /health).

Add new business routes to `router` below with `@router.get(...)` /
`@router.post(...)`, not directly to the FastAPI app in api/main.py.
When /v2 is needed, add a sibling `mind.api.v2` package the same way so
both versions can run side by side.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request

from mind.api.instrumentation import instrumented_call
from mind.contracts.related_tasks import RelatedTask, RelatedTasksResponse
from mind.embeddings import find_related
from mind.embeddings.base import VectorStore

router = APIRouter(prefix="/v1")


def get_vector_store(request: Request) -> VectorStore:
    """Opened once in api/main.py's lifespan. Depends(), not a direct
    request.app.state read, so tests can override it without mutating
    the shared app object other test files also import."""
    return request.app.state.vector_store


VectorStoreDep = Annotated[VectorStore, Depends(get_vector_store)]


@router.get("/tasks/{task_id}/related", response_model=RelatedTasksResponse)
async def get_related_tasks(task_id: str, store: VectorStoreDep) -> RelatedTasksResponse:
    """#472. Always 200 -- related: [] covers an unindexed task_id, an
    empty index, or nothing above threshold (see embeddings/README.md)."""
    with instrumented_call("find_related"):
        results = await find_related(store, task_id)
    return RelatedTasksResponse(
        related=[RelatedTask(task_id=r.task_id, similarity=r.similarity) for r in results]
    )
