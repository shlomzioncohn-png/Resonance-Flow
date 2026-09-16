from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Absolute, not ".env" -- a relative path is resolved against the current
# working directory, which silently finds nothing if pytest (or anything
# else) is ever run from somewhere other than teams/mind/.
_ENV_FILE = Path(__file__).resolve().parents[3] / ".env"

# Local copy of the model weights (models/ is gitignored -- never
# committed). If this directory exists, generate.py loads the model
# straight from disk with no network call at all. Falls back to
# EMBEDDING_MODEL_NAME (a Hugging Face Hub id, downloaded and cached by
# sentence-transformers) if it doesn't -- see generate.py.
EMBEDDING_MODEL_LOCAL_DIR = Path(__file__).resolve().parents[3] / "models" / "bge-small-en-v1.5"


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

    # --- Embedding model -----------------------------------------------
    embedding_model_name: str = "BAAI/bge-small-en-v1.5"
    # Must match whatever generate.py's embed() actually returns -- the
    # pgvector column is created as VECTOR(embedding_dim).
    embedding_dim: int = 384
    max_seq_length: int = 512  # tokens; longer text is truncated, see generate.py

    # BGE is an asymmetric retrieval model: queries need this instruction
    # prefix prepended before encoding; stored passages/documents do not.
    # See generate.py's embed()/embed_batch() `is_query` parameter.
    bge_query_instruction: str = "Represent this sentence for searching relevant passages: "

    # pgvector's HNSW ef_search defaults to 40 (speed over recall); 400
    # lifts recall@10 from 61% to 86% at 10k rows for ~3.5ms extra p95,
    # still well under budget. Named search "effort", not "ef_search" --
    # this module's public config shouldn't name a pgvector-specific knob.
    vector_search_effort: int = 400

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


# Only fields with no ordering hazard are re-exported eagerly here. DB-facing
# fields (table name, search effort) are read lazily via get_settings() at
# the point of use instead -- e.g. vector_store_table must reflect whatever
# tests/conftest.py sets *before* this module is first imported, and an
# eager read here would freeze it before that override lands.
EMBEDDING_MODEL_NAME = get_settings().embedding_model_name
EMBEDDING_DIM = get_settings().embedding_dim
MAX_SEQ_LENGTH = get_settings().max_seq_length
BGE_QUERY_INSTRUCTION = get_settings().bge_query_instruction
DUPLICATE_SIMILARITY_THRESHOLD = get_settings().duplicate_similarity_threshold
