import os
from pathlib import Path

# --- Embedding model ------------------------------------------------------
EMBEDDING_MODEL_NAME = "BAAI/bge-small-en-v1.5"
# Local copy of the model weights (models/ is gitignored -- never
# committed). If this directory exists, generate.py loads the model
# straight from disk with no network call at all. Falls back to
# EMBEDDING_MODEL_NAME (a Hugging Face Hub id, downloaded and cached by
# sentence-transformers) if it doesn't -- see generate.py.
EMBEDDING_MODEL_LOCAL_DIR = Path(__file__).resolve().parents[3] / "models" / "bge-small-en-v1.5"
EMBEDDING_DIM = 384
MAX_SEQ_LENGTH = 512  # tokens; longer text is truncated, see generate.py

# BGE is an asymmetric retrieval model: queries need this instruction
# prefix prepended before encoding; stored passages/documents do not.
# See generate.py's embed()/embed_batch() `is_query` parameter.
BGE_QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: "

# --- Database connection (pgvector / Postgres) --------------------------
# Overridable via env vars so this works the same in dev, CI, and prod.
PG_DSN = os.environ.get(
    "MIND_PG_DSN",
    "dbname=mind user=postgres password=postgres host=localhost port=5432",
)

PG_TABLE = "task_embeddings"

# HNSW query-time accuracy/speed knob (pgvector's default is 40, tuned for
# speed over recall). Raising this to 400 measurably improves recall
# (ANN-vs-exact recall@10 goes from 61% to 86% at 10,000 rows) while query
# p95 only moves from ~2ms to ~5.5ms -- still far under the 300ms budget.
# Re-measure if this table grows far past 10k rows.
HNSW_EF_SEARCH = 400

# --- Duplicate-detection --------------------------------------------------
# TODO(tune-me): placeholder cosine-similarity threshold above which two
# tasks are considered "likely duplicates". 0.9 is a guess, not a measured
# value. Revisit once we have a labeled set of real duplicate/non-duplicate
# task pairs from production data, and pick this properly via a
# precision/recall sweep. Do not ship this value to prod without re-tuning.
DUPLICATE_SIMILARITY_THRESHOLD = 0.90
