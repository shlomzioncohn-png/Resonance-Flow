"""
The contract every vector-database backend must satisfy.

Deliberately dependency-free (only `abc`) so this interface can be
imported -- for type-checking, mocking in tests, or sketching a new
backend -- without requiring psycopg or any other backend-specific
package to be installed.

pgvector is the team's chosen backend -- see
`../../../benchmarks/vector-store-comparison.md` for the comparison and
decision. Anything else the app wants to use later implements this class;
the rest of the app never sees this class directly, only the module-level
functions in `mind/embeddings/__init__.py`, which delegate to whichever
implementation `factory.py` hands back.

Methods are `async def`: the current backend (pgvector via psycopg) talks
to Postgres over an async connection so it doesn't block the event loop,
and any future backend implementing this interface needs to honor the
same contract.
"""

from abc import ABC, abstractmethod


class VectorStore(ABC):
    @abstractmethod
    async def upsert(self, task_id: str, vector: list[float]) -> None:
        """Save or update the vector for a given task_id."""

    @abstractmethod
    async def search(self, query_vector: list[float], k: int) -> list[str]:
        """Return up to k task_ids most similar to query_vector, best match first."""

    @abstractmethod
    async def delete(self, task_id: str) -> None:
        """Remove the vector for a given task_id, if it exists.

        Deleting a task_id that isn't stored is not an error -- same
        no-op-if-absent semantics as SQL's DELETE.
        """

    @abstractmethod
    async def clear(self) -> None:
        """Remove every stored vector. Intended for test teardown, not production use."""
