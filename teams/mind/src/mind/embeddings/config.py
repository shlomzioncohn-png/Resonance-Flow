import os

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

# --- Embedding vector shape ------------------------------------------------
# Must match whatever embed() in embeddings.py actually returns -- the
# pgvector column is created as VECTOR(EMBEDDING_DIM).
EMBEDDING_DIM = 256

# --- Duplicate-detection --------------------------------------------------
# TODO(tune-me): placeholder cosine-similarity threshold above which two
# tasks are considered "likely duplicates". 0.9 is a guess, not a measured
# value. Revisit once we have a labeled set of real duplicate/non-duplicate
# task pairs from production data, and pick this properly via a
# precision/recall sweep. Do not ship this value to prod without re-tuning.
DUPLICATE_SIMILARITY_THRESHOLD = 0.90
