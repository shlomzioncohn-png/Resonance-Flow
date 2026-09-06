from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Config for this module, read from env vars (or a `.env` file).

    Backed by Pydantic so a missing/malformed value fails fast at import
    time with a clear error, instead of silently falling back to a
    hardcoded default (previous behavior defaulted `vector_store_dsn` to
    `user=postgres password=postgres` -- fine for a laptop, dangerous if
    it were ever forgotten in a real environment) or blowing up later,
    deep inside a request, with a confusing psycopg error.

    Field names deliberately don't mention Postgres/pgvector -- this
    module's public interface (`mind.embeddings`) hides which database
    backs it, and that should hold for its configuration too. Only
    `pgvector_store.py` (via `factory.py`) knows the connection string is
    actually a libpq DSN.
    """

    model_config = SettingsConfigDict(env_prefix="MIND_", env_file=".env", extra="ignore")

    vector_store_dsn: str
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


settings = Settings()

PG_DSN = settings.vector_store_dsn
PG_TABLE = settings.vector_store_table
HNSW_EF_SEARCH = settings.hnsw_ef_search
EMBEDDING_DIM = settings.embedding_dim
DUPLICATE_SIMILARITY_THRESHOLD = settings.duplicate_similarity_threshold
