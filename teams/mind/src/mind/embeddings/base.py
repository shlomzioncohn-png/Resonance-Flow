"""
The contract every vector-database backend must satisfy. Dependency-free
of any backend-specific package, so it's importable without psycopg etc.

pgvector is the chosen backend (see
benchmarks/vector-store-comparison.md); callers construct
pgvector_store.PgVectorStore directly.
"""

from abc import ABC, abstractmethod
from uuid import UUID

from pydantic import BaseModel, Field

# Inbox's built-in System tenant (exists in every Inbox database). Used by
# local loaders and benchmarks that have no real tenant.
DEMO_TENANT_ID = UUID("00000000-0000-0000-0000-000000000001")


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
    confidence: float | None = Field(
        default=None,
        ge=0.0,
        le=1.0,
        description="How sure this hit is a real match, as distance above "
        "the threshold that selected it (#476) -- not the raw similarity. "
        "None on a raw, unfiltered search() hit; set once a caller applies "
        "its own threshold (duplicate_detection.py, related_tasks.py, "
        "dependency_resolution.py).",
    )


class VectorStore(ABC):
    """One tenant's slice of the index (#841). Get it from VectorIndex.for_tenant()."""

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
        """Up to k results, best match first. Both excludes filter inside
        the query itself -- post-hoc filtering could leave k short."""
        ...

    @abstractmethod
    async def get_vector(self, task_id: str) -> list[float] | None:
        """Stored embedding for task_id, or None if not indexed."""
        ...

    @abstractmethod
    async def delete(self, task_id: str) -> None:
        """Remove a task_id's vector. No-op if it isn't stored."""
        ...


class VectorIndex(ABC):
    """The whole index. Reads and writes go through for_tenant(), so no
    caller can search across tenants by mistake."""

    @abstractmethod
    def for_tenant(self, tenant_id: UUID) -> VectorStore: ...

    @abstractmethod
    async def tenant_of(self, task_id: str) -> UUID | None:
        """The tenant a task is indexed under, for callers that only have
        a task_id. None if not indexed, or indexed before #841."""
        ...
