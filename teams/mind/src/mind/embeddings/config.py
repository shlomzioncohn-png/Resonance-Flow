from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Local copy of the model weights (models/ is gitignored -- never
# committed). If this directory exists, generate.py loads the model
# straight from disk with no network call at all. Falls back to
# EMBEDDING_MODEL_NAME (a Hugging Face Hub id, downloaded and cached by
# sentence-transformers) if it doesn't -- see generate.py.
EMBEDDING_MODEL_LOCAL_DIR = Path(__file__).resolve().parents[3] / "models" / "bge-small-en-v1.5"


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

    # HNSW query-time accuracy/speed knob (pgvector's default is 40, tuned
    # for speed over recall). Raising this to 400 measurably improves
    # recall (ANN-vs-exact recall@10 goes from 61% to 86% at 10,000 rows)
    # while query p95 only moves from ~2ms to ~5.5ms -- still far under
    # the 300ms budget. Re-measure if this table grows far past 10k rows.
    hnsw_ef_search: int = 400

    # TODO(tune-me): placeholder cosine-similarity threshold above which
    # two tasks are considered "likely duplicates". 0.9 is a guess, not a
    # measured value. Revisit once we have a labeled set of real
    # duplicate/non-duplicate task pairs from production data, and pick
    # this properly via a precision/recall sweep. Do not ship this value
    # to prod without re-tuning.
    duplicate_similarity_threshold: float = 0.90


# mypy can't see that BaseSettings populates required fields (vector_store_dsn)
# from env vars at runtime, so it flags this as a missing argument. Field stays
# required (no default) -- this ignore doesn't paper over the fail-fast
# behavior described above, it just tells mypy the call is intentional.
settings = Settings()  # type: ignore[call-arg]

PG_DSN = settings.vector_store_dsn
PG_TABLE = settings.vector_store_table
EMBEDDING_MODEL_NAME = settings.embedding_model_name
EMBEDDING_DIM = settings.embedding_dim
MAX_SEQ_LENGTH = settings.max_seq_length
BGE_QUERY_INSTRUCTION = settings.bge_query_instruction
HNSW_EF_SEARCH = settings.hnsw_ef_search
DUPLICATE_SIMILARITY_THRESHOLD = settings.duplicate_similarity_threshold
