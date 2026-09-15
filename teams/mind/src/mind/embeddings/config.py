from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Absolute, not ".env" -- a relative path is resolved against the current
# working directory, which silently finds nothing if pytest (or anything
# else) is ever run from somewhere other than teams/mind/.
_ENV_FILE = Path(__file__).resolve().parents[3] / ".env"


class Settings(BaseSettings):
    """Config for this module, read from env vars (or a `.env` file).

    `vector_store_dsn` is intentionally `str | None`, not a required
    field: this class is constructed once, lazily, the first time
    *anything* in this module needs *any* setting -- including
    `embedding_dim`, which pure-CPU code (embed()) needs and which has
    nothing to do with the database. Making the whole class fail without
    a DSN would mean embed()-only tests (and anything else that never
    touches the vector store) couldn't run without a database either,
    defeating the point of this module's lazy-DB-access design (see the
    package docstring in `__init__.py`).

    Fail-fast for the DSN specifically still happens -- see
    `pgvector_store.py`'s `_require_dsn()`, called only when something
    actually tries to talk to Postgres, with a clear error instead of a
    hardcoded default (`user=postgres password=postgres`, the previous
    behavior -- fine for a laptop, dangerous if forgotten in a real
    environment) or a confusing psycopg error deep inside a request.

    Field names deliberately don't mention Postgres/pgvector -- this
    module's public interface (`mind.embeddings`) hides which database
    backs it, and that should hold for its configuration too. Only
    `pgvector_store.py` (via `factory.py`) knows the connection string is
    actually a libpq DSN.
    """

    model_config = SettingsConfigDict(env_prefix="MIND_", env_file=_ENV_FILE, extra="ignore")

    vector_store_dsn: str | None = None
    vector_store_table: str = "task_embeddings"

    # HNSW query-time accuracy/speed knob (pgvector's default is 40, tuned
    # for speed over recall). Raising this to 400 measurably improves
    # recall (ANN-vs-exact recall@10 goes from 61% to 86% at 10,000 rows)
    # while query p95 only moves from ~2ms to ~5.5ms -- still far under
    # the 300ms budget. Re-measure if this table grows far past 10k rows.
    hnsw_ef_search: int = 400

    # Must match whatever embed() in embeddings.py actually returns -- the
    # pgvector column is created as VECTOR(embedding_dim).
    embedding_dim: int = 256

    # TODO(tune-me): placeholder cosine-similarity threshold above which
    # two tasks are considered "likely duplicates". 0.9 is a guess, not a
    # measured value. Revisit once we have a labeled set of real
    # duplicate/non-duplicate task pairs from production data, and pick
    # this properly via a precision/recall sweep. Do not ship this value
    # to prod without re-tuning.
    duplicate_similarity_threshold: float = 0.90


@lru_cache
def get_settings() -> Settings:
    """Construct (once, cached) and return the Settings.

    Lazy on purpose: importing this module -- or `mind.embeddings` as a
    whole -- must never require a database DSN. Nothing in this file
    calls this at import time; each field below is read through it only
    when a caller actually asks for that field.
    """
    return Settings()


PG_DSN = get_settings().vector_store_dsn
PG_TABLE = get_settings().vector_store_table
HNSW_EF_SEARCH = get_settings().hnsw_ef_search
EMBEDDING_DIM = get_settings().embedding_dim
DUPLICATE_SIMILARITY_THRESHOLD = get_settings().duplicate_similarity_threshold
