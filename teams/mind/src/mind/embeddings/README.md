# embeddings

Vectors, semantic search, duplicate/related-task detection.

## Decisions (closed)

- **Vector store: Chosen: pgvector** — see
  [`../../../benchmarks/vector-store-comparison.md`](../../../benchmarks/vector-store-comparison.md)
  for the comparison, decision, and validation results (latency and recall,
  at the real 10,000-vector load target).
- **Embedding model: Chosen: `BAAI/bge-small-en-v1.5`.** Local, open-source
  (MIT), CPU-only, output dimension **384** (`EMBEDDING_DIM` in
  `config.py`; the pgvector table schema in `pgvector_store.py` is created
  with `VECTOR(384)` to match). English-only by design — MIND only needs
  English, no multilingual requirement. Retrieval-tuned (query-vs-passage
  matching), a better fit than a generic sentence-similarity model (e.g.
  all-MiniLM-L6-v2, an earlier candidate used while BGE's weights were
  blocked by a content filter on the original dev machine) for this
  module's actual query-vs-corpus use case. Being asymmetric, it requires
  queries (not stored passages) to be embedded with an instruction prefix
  — see `generate.py`'s module docstring for the full reasoning, the
  query/passage asymmetry, and how text longer than the model's context
  window is handled (truncation, not chunk-and-average). Weights ship as a
  local folder (`models/bge-small-en-v1.5/`, gitignored) so loading needs
  no network access; see `config.EMBEDDING_MODEL_LOCAL_DIR`.
- **Load target** (week 8, provisional): 10,000+ embedded records, semantic
  query at <300ms p95.

## What's here

```
__init__.py         the public interface -- embed() / embed_batch() / upsert() / search() / delete()
generate.py          embed() / embed_batch() -- the real model, truncation policy
base.py              VectorStore abstract interface (the "contract") -- dependency-free
pgvector_store.py    the ONLY file that knows about Postgres/pgvector
factory.py           single switch that picks the backend implementation
config.py            model name/path/dim, connection settings, table name, HNSW tuning, duplicate-detection threshold
loader.py            fetches + verifies the real FUNSD manifest slice (see below)
ingest.py            pipeline: loader -> embed_batch -> upsert(source_ref, vector)
verify.py            checks table row count == manifest record count
```

Nothing outside `__init__.py` should be imported by callers elsewhere in
the app. `base.py` deliberately has zero backend-specific dependencies
(only `abc`) so the interface itself can be imported -- for type-checking,
mocking in tests, or sketching a new backend -- without requiring
psycopg2 to be installed; only `pgvector_store.py` (reached through
`factory.py`) actually imports it.

## Real data: the FUNSD slice (not R2 -- see why)

There is no R2 loader anywhere in this repo, and no R2 storage in use
(Bridge's object-storage work uses MinIO). What exists instead is a real
2-document FUNSD slice from `yonit/funsd-foundation-gate` (PR #141, not
yet merged as of this branch -- pulled in here as
`eval/datasets/.manifest.json` + `.sha256`). Per that PR's own convention,
the raw files are **not committed** (`eval/datasets/README.md`: "manifests
+ checksums only, never the corpora"); `loader.py` downloads them from
FUNSD's public source on first use, verifies each against the committed
SHA-256, and caches them under the already-gitignored
`eval/datasets/local-data/`.

```bash
python -m mind.embeddings.ingest    # fetch + verify + embed the real FUNSD slice, upsert into pgvector
python -m mind.embeddings.verify    # table row count vs. manifest record count
```

## Setup

Requires a local Postgres with the `vector` extension (build steps in
`pgvector`'s repo: https://github.com/pgvector/pgvector), and the `mind`
package installed with its normal dependencies:

```bash
make dev        # from teams/mind/ -- installs everything, including this module's deps
export MIND_PG_DSN="dbname=mind user=postgres password=postgres host=localhost port=5432"
```

## Run the tests

```bash
pytest          # from teams/mind/ -- picks up tests/test_vector_store.py and tests/test_embeddings.py automatically
```

## Using the interface elsewhere in the app

```python
from mind.embeddings import embed, upsert, search, delete

# Indexing a record: is_query defaults to False.
vector = embed(task.title + " " + task.description)
upsert(task.id, vector)

# Searching: is_query=True is required for correct BGE retrieval quality.
query_vector = embed(user_query_text, is_query=True)
similar_task_ids = search(query_vector, k=5)

delete(task.id)  # e.g. when the task itself is deleted
```

No caller anywhere else in the codebase should import `pgvector_store.py`
directly, or know that Postgres is involved at all. If we switch databases
later, only `pgvector_store.py` + `factory.py` need to change.

## Known limitations (flag these in review)

- `DUPLICATE_SIMILARITY_THRESHOLD` in `config.py` is an untuned placeholder,
  and `search()` doesn't return a similarity score to apply it against yet.
- Real-data coverage is still just the 2-document FUNSD slice from PR #141
  -- enough to validate the pipeline shape end-to-end, not enough for a
  real recall/quality read on production data.
