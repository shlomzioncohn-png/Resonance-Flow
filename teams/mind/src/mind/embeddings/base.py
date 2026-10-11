"""
The contract every vector-database backend must satisfy. Dependency-free
of any backend-specific package, so it's importable without psycopg etc.

pgvector is the chosen backend (see
benchmarks/vector-store-comparison.md); callers construct
pgvector_store.PgVectorStore directly.
"""

from abc import ABC, abstractmethod

from pydantic import BaseModel, Field


class SearchResult(BaseModel):
    """One search() hit: task_id and similarity."""

    task_id: str
    similarity: float = Field(
        ge=-1.0,
        le=1.0,
        description="Cosine similarity, comparable to Settings.duplicate_similarity_threshold",
    )


class VectorStore(ABC):
    @abstractmethod
    async def open(self) -> None:
        """Eagerly initialize. Optional -- other methods init lazily."""
        ...

    @abstractmethod
    async def close(self) -> None:
        """Release resources. Call on graceful shutdown."""
        ...

    @abstractmethod
    async def upsert(self, task_id: str, vector: list[float]) -> None:
        """Save or update the vector for a given task_id."""
        ...

    @abstractmethod
    async def search(self, query_vector: list[float], k: int) -> list[SearchResult]:
        """Up to k results most similar to query_vector, best match first."""
        ...

    @abstractmethod
    async def delete(self, task_id: str) -> None:
        """Remove a task_id's vector. No-op if it isn't stored."""
        ...

    @abstractmethod
    async def clear(self) -> None:
        """Remove every stored vector. Test teardown only."""
        ...
