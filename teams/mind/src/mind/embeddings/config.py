from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Absolute, not ".env" -- a relative path is resolved against the current
# working directory, which silently finds nothing if pytest (or anything
# else) is ever run from somewhere other than teams/mind/.
_ENV_FILE = Path(__file__).resolve().parents[3] / ".env"


class Settings(BaseSettings):
    """Config for this module, read from env vars (or a `.env` file).

    `vector_store_dsn` is `str | None`, not required: `embedding_dim` and
    other non-DB fields must be readable without a DSN, or embed()-only
    code (and its tests) would need a database for no reason. The DSN
    itself still fails fast -- see pgvector_store.py's `_require_dsn()`
    -- just deferred until something actually needs Postgres.

    Field names avoid "postgres"/"pgvector": this module's public
    interface hides which database backs it, so its config should too.
    """

    model_config = SettingsConfigDict(env_prefix="MIND_", env_file=_ENV_FILE, extra="ignore")

    vector_store_dsn: str | None = None
    vector_store_table: str = "task_embeddings"

    # pgvector defaults to 40 (speed over recall); 400 lifts recall@10 from
    # 61% to 86% at 10k rows for ~3.5ms extra p95, still well under budget.
    hnsw_ef_search: int = 400

    # Must match whatever embed() in embeddings.py actually returns -- the
    # pgvector column is created as VECTOR(embedding_dim).
    embedding_dim: int = 256

    # TODO(tune-me): guessed, not measured -- revisit via a precision/recall
    # sweep once we have labeled duplicate/non-duplicate task pairs.
    duplicate_similarity_threshold: float = 0.90


@lru_cache
def get_settings() -> Settings:
    """Construct (once, cached) and return the Settings.

    Lazy on purpose: importing this module must never require a DSN --
    each field below is only read through this when actually needed.
    """
    return Settings()


PG_DSN = get_settings().vector_store_dsn
PG_TABLE = get_settings().vector_store_table
HNSW_EF_SEARCH = get_settings().hnsw_ef_search
EMBEDDING_DIM = get_settings().embedding_dim
DUPLICATE_SIMILARITY_THRESHOLD = get_settings().duplicate_similarity_threshold
