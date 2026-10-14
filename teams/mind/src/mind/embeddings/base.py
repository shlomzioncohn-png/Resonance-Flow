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
    is_completed: bool = Field(
        default=False,
        description="DONE/REJECTED/CANCELLED -- any status the index treats as finished.",
    )


class VectorStore(ABC):
    @abstractmethod
    async def open(self) -> None:
        """Eagerly initialize (confirms the schema is migrated). Optional -- other methods init lazily."""
        ...

    @abstractmethod
    async def close(self) -> None:
        """Release resources. Call on graceful shutdown."""
        ...

    @abstractmethod
    async def upsert(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> None:
        """Save or update the vector for a given task_id. content_hash (a
        hash of whatever text embed() was given) lets a caller skip
        re-embedding unchanged text later via get_content_hash()."""
        ...

    @abstractmethod
    async def upsert_if_absent(
        self, task_id: str, vector: list[float], *, content_hash: str | None = None
    ) -> bool:
        """Insert only if absent (unlike upsert's insert-or-update).
        True if inserted, False if a row already existed."""
        ...

    @abstractmethod
    async def get_content_hash(self, task_id: str) -> str | None:
        """The content_hash last passed to upsert()/upsert_if_absent(), or
        None if the task isn't indexed or was indexed without one."""
        ...

    @abstractmethod
    async def mark_completed(self, task_id: str) -> bool:
        """True if a row existed and was updated, False if unknown."""
        ...

    @abstractmethod
    async def mark_incomplete(self, task_id: str) -> bool:
        """Reset is_completed to False (e.g. a DONE task got reopened).
        True if a row existed and was updated, False if unknown."""
        ...

    @abstractmethod
    async def search(
        self,
        query_vector: list[float],
        k: int,
        *,
        exclude_completed: bool = False,
        exclude_task_id: str | None = None,
    ) -> list[SearchResult]:
        """Up to k results most similar to query_vector, best match first.
        exclude_completed=True excludes is_completed rows inside the query
        itself, so k only ever counts open candidates -- filtering them out
        afterward in Python can't recover a real match that a small k
        never fetched in the first place. exclude_task_id does the same for
        a single task_id -- e.g. #472's related-tasks query, where
        query_vector is that task's own stored vector and would otherwise
        always match itself first."""
        ...

    @abstractmethod
    async def get_vector(self, task_id: str) -> list[float] | None:
        """The embedding already stored for task_id, or None if it isn't
        indexed yet -- #472's related-tasks query reuses this instead of
        re-embedding text that's already been embedded once."""
        ...

    @abstractmethod
    async def delete(self, task_id: str) -> None:
        """Remove a task_id's vector. No-op if it isn't stored."""
        ...

    @abstractmethod
    async def clear(self) -> None:
        """Remove every stored vector. Test teardown only."""
        ...
