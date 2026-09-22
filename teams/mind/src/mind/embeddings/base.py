"""
The contract every vector-database backend must satisfy.

Deliberately dependency-free of any *backend-specific* package (only
`abc` and `pydantic`, the latter already a core project dependency, not
something specific to whichever database ends up behind this interface)
so this interface can be imported -- for type-checking, mocking in
tests, or sketching a new backend -- without requiring psycopg or any
other backend-specific package to be installed.

Methods are `async def`: the current backend (pgvector via psycopg) talks
to Postgres over an async connection so it doesn't block the event loop,
and any future backend implementing this interface needs to honor the
same contract.

pgvector is the team's chosen backend -- see
`../../../benchmarks/vector-store-comparison.md` for the comparison and
decision. Anything else the app wants to use later implements this class
too; callers construct whichever implementation they need directly (see
`pgvector_store.PgVectorStore`).
"""

from abc import ABC, abstractmethod

from pydantic import BaseModel, Field


class SearchResult(BaseModel):
    """One search() hit: which task_id, and how similar it was."""

    task_id: str
    similarity: float = Field(
        ge=-1.0,
        le=1.0,
        description="Cosine similarity, comparable to Settings.duplicate_similarity_threshold",
    )


class VectorStore(ABC):
    @abstractmethod
    async def open(self) -> None:
        """Eagerly initialize the store (open connections, ensure schema).

        Optional -- upsert/search/delete/clear initialize lazily on first
        use otherwise. Call explicitly to tie resources to a known,
        long-lived event loop (e.g. a FastAPI lifespan handler).
        """
        ...

    @abstractmethod
    async def close(self) -> None:
        """Release resources acquired by open() or by lazy initialization
        (e.g. close a connection pool). Call this on graceful shutdown."""
        ...

    @abstractmethod
    async def upsert(self, task_id: str, vector: list[float]) -> None:
        """Save or update the vector for a given task_id."""
        ...

    @abstractmethod
    async def search(self, query_vector: list[float], k: int) -> list[SearchResult]:
        """Return up to k results most similar to query_vector, best match first."""
        ...

    @abstractmethod
    async def delete(self, task_id: str) -> None:
        """Remove the vector for a given task_id, if it exists.

        Deleting a task_id that isn't stored is not an error -- same
        no-op-if-absent semantics as SQL's DELETE.
        """
        ...

    @abstractmethod
    async def clear(self) -> None:
        """Remove every stored vector. Intended for test teardown, not production use."""
        ...
