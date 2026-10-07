# embeddings

Vectors, semantic search, duplicate/related-task detection.

## Decisions (closed)

- **Vector store: Chosen: pgvector** — see
  [`../../../benchmarks/vector-store-comparison.md`](../../../benchmarks/vector-store-comparison.md)
  for the comparison, decision, and validation results (latency and recall,
  at the real 10,000-vector load target).
- **Embedding model: Chosen: `BAAI/bge-small-en-v1.5`.** Local, open-source
  (MIT), CPU-only, output dimension **384** (`EMBEDDING_DIM` in
  `embeddings.py`; the pgvector table schema in `pgvector_store.py` is
  created with `VECTOR(384)` to match, via `VectorStoreSettings.embedding_dim`).
  English-only by design — MIND only needs English, no multilingual
  requirement. Retrieval-tuned (query-vs-passage matching), a better fit
  than a generic sentence-similarity model (e.g. all-MiniLM-L6-v2, an
  earlier candidate) for this module's actual query-vs-corpus use case.
  Being asymmetric, it requires queries (not stored passages) to be
  embedded with an instruction prefix — see `embeddings.py`'s module
  docstring for the full reasoning, the query/passage asymmetry, and how
  text longer than the model's context window is handled (truncation, not
  chunk-and-average). Weights ship as a local folder
  (`models/bge-small-en-v1.5/`, gitignored) so loading needs no network
  access; see `embeddings.EMBEDDING_MODEL_LOCAL_DIR`.
- **Load target** (week 8, provisional): 10,000+ embedded records, semantic
  query at <300ms p95.

## What's here

```
__init__.py          the public interface -- embed() / embed_batch() (+ async wrappers), SearchResult, VectorStore
embeddings.py         embed() / embed_batch() / embed_async() / embed_batch_async() -- the real model, truncation policy
base.py               VectorStore abstract interface (the "contract") + SearchResult -- dependency-free (besides pydantic)
pgvector_store.py     the ONLY file that knows about Postgres/pgvector
loader.py             fetches + verifies the real FUNSD manifest slice (see below)
ingest.py              pipeline: loader -> embed_batch_async -> PgVectorStore.upsert
verify.py              checks table row count == manifest record count
```

Config lives one level up, at `mind.config` (`Settings`/`VectorStoreSettings`)
-- it's app-wide, not specific to this module.

Nothing outside `__init__.py` should be imported by callers elsewhere in
the app, except `PgVectorStore` itself, which callers construct directly
(see "Using the interface" below) -- there's no factory/singleton
indirection. `base.py` deliberately has zero *backend-specific*
dependencies (only `abc` and `pydantic`, already a core project
dependency) so the interface itself can be imported -- for type-checking,
mocking in tests, or sketching a new backend -- without requiring
psycopg to be installed; only `pgvector_store.py` actually imports it.

`upsert`/`search`/`delete`/`clear`/`open`/`close` are all `async def` --
the store talks to Postgres over psycopg's async API so it doesn't block
the event loop under FastAPI's `async def` route handlers.

`open()`/`close()` control the backing store's resources (e.g. a
connection pool) explicitly -- call them from a FastAPI lifespan handler
so the pool's lifetime is tied to the app's, on a known event loop, from
the start (see `api/main.py` for the real one):

```python
from contextlib import asynccontextmanager
from fastapi import FastAPI
from mind.config import get_settings
from mind.embeddings.pgvector_store import PgVectorStore

@asynccontextmanager
async def lifespan(app: FastAPI):
    store = PgVectorStore(get_settings().vector_store)
    try:
        await store.open()
        app.state.vector_store = store
        yield
    finally:
        await store.close()

app = FastAPI(lifespan=lifespan)
```

If you never call `open()`, the store still initializes itself lazily on
first `upsert`/`search`/`delete`/`clear` -- fine for standalone scripts,
but for a long-running server prefer the explicit form above (see
`base.VectorStore.open()`'s docstring for why).

## Real data: the FUNSD slice (not R2 -- see why)

There is no R2 loader anywhere in this repo, and no R2 storage in use
(Bridge's object-storage work uses MinIO). What exists instead is a real
2-document FUNSD slice from `yonit/funsd-foundation-gate` (PR #141) --
`eval/datasets/.manifest.json` + `.sha256`. Per that PR's own convention,
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
export MIND_ENVIRONMENT=local
export MIND_VECTOR_STORE__DSN="postgresql://mind:mind@localhost:5432/mind"
```

`mind.config.Settings` fails fast if `MIND_ENVIRONMENT` or
`MIND_VECTOR_STORE__DSN` aren't set, rather than silently connecting with
guessed/default credentials. Importing `mind.embeddings` itself doesn't
require either -- only constructing a `PgVectorStore` (or calling
`mind.config.get_settings()`) does. `embed()` does need the real model,
though: first call downloads `BAAI/bge-small-en-v1.5` from the Hugging
Face Hub unless `models/bge-small-en-v1.5/` already exists locally.

## Run the tests

```bash
pytest          # from teams/mind/ -- picks up tests/test_vector_store.py and tests/test_embeddings.py automatically
```

## Using the interface elsewhere in the app

```python
from mind.config import get_settings
from mind.embeddings import embed, embed_async
from mind.embeddings.pgvector_store import PgVectorStore

store = PgVectorStore(get_settings().vector_store)  # or reuse app.state.vector_store

# Indexing a record, or a passage-to-passage comparison (e.g. duplicate
# detection): is_query defaults to False -- do NOT set it for either case.
vector = embed(task.title + " " + task.description)
await store.upsert(task.id, vector)

similar = await store.search(vector, k=5)  # [SearchResult(task_id=..., similarity=...), ...], best match first

await store.delete(task.id)  # e.g. when the task itself is deleted
```

A future natural-language search endpoint (#472) searching by raw query
text, not an existing task's own text, must embed that query with
`is_query=True` (BGE's documented asymmetric-retrieval usage) before
calling `store.search()` -- forgetting the flag doesn't raise, it just
silently inflates the similarity score (see `embeddings.py`'s module
docstring's "Query/passage asymmetry" section).

`embed()`/`embed_batch()` are plain (non-async) functions -- calling them
directly from an `async def` route handler blocks the event loop for the
duration of model inference. Use `embed_async()`/`embed_batch_async()`
instead from async callers (same signature, offloads to a worker thread
via `asyncio.to_thread`):

```python
from mind.embeddings import embed_async

vector = await embed_async(task.title + " " + task.description)
await store.upsert(task.id, vector)
```

No caller anywhere else in the codebase should know that Postgres is
involved. If we switch databases later, only `pgvector_store.py` (a new
sibling implementing `base.VectorStore`) needs to change.

## Known limitations (flag these in review)

- `Settings.duplicate_similarity_threshold` (`mind.config`) is an untuned
  placeholder -- `search()` now returns a cosine similarity per result,
  but no caller applies the threshold to it yet.
- Real-data coverage is still just the 2-document FUNSD slice from PR #141
  -- enough to validate the pipeline shape end-to-end, not enough for a
  real recall/quality read on production data.
