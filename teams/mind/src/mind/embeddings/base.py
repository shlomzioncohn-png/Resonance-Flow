"""
The contract every vector-database backend must satisfy.

Deliberately dependency-free (only `abc`) so this interface can be
imported -- for type-checking, mocking in tests, or sketching a new
backend -- without requiring psycopg2 or any other backend-specific
package to be installed.

pgvector is the team's chosen backend -- see
`../../../benchmarks/vector-store-comparison.md` for the comparison and
decision. Anything else the app wants to use later implements this class;
the rest of the app never sees this class directly, only the module-level
functions in `mind/embeddings/__init__.py`, which delegate to whichever
implementation `factory.py` hands back.
"""

from abc import ABC, abstractmethod


class VectorStore(ABC):
    @abstractmethod
    def upsert(self, task_id: str, vector: list[float]) -> None:
        """Save or update the vector for a given task_id."""
        raise NotImplementedError

    @abstractmethod
    def search(self, query_vector: list[float], k: int) -> list[str]:
        """Return up to k task_ids most similar to query_vector, best match first."""
        raise NotImplementedError
