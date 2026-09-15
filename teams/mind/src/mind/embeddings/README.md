# embeddings

Vectors, semantic search, duplicate/related-task detection.

## Decisions (closed)

- **Vector store: Chosen: pgvector** — see
  [`../../../benchmarks/vector-store-comparison.md`](../../../benchmarks/vector-store-comparison.md)
  for the comparison, decision, and validation results (latency and recall,
  at the real 10,000-vector load target).
- **Embedding model: still a placeholder (`embeddings.py`, HashingVectorizer)
  — not a real semantic model yet, deliberately.** See the comparison doc's
  "Validation" section for what that costs in practice (near-zero recall
  on paraphrased near-duplicates); swapping it is tracked separately and
  is a one-function change (see `embeddings.py`'s docstring).
- **Load target** (week 8, provisional): 10,000+ embedded records, semantic
  query at <300ms p95.

## What's here

```
__init__.py         the public interface -- embed() / upsert() / search() / delete() / clear() / open() / close()
embeddings.py       embed() -- the (placeholder) text vectorizer
base.py             VectorStore abstract interface (the "contract") + SearchResult -- dependency-free (besides pydantic)
pgvector_store.py   the ONLY file that knows about Postgres/pgvector
factory.py          single switch that picks the backend implementation
config.py           Pydantic Settings (lazy -- see get_settings()): connection DSN, table name, HNSW tuning, duplicate-detection threshold
```

Nothing outside `__init__.py` should be imported by callers elsewhere in
the app. `base.py` deliberately has zero *backend-specific* dependencies
(only `abc` and `pydantic`, already a core project dependency) so the
interface itself can be imported -- for type-checking, mocking in tests,
or sketching a new backend -- without requiring psycopg to be installed;
only `pgvector_store.py` (reached through `factory.py`) actually imports
it.

`upsert`/`search`/`delete`/`clear`/`open`/`close` are all `async def` --
the store talks to Postgres over psycopg's async API so it doesn't block
the event loop under FastAPI's `async def` route handlers.

`open()`/`close()` control the backing store's resources (e.g. a
connection pool) explicitly -- call them from a FastAPI lifespan handler
so the pool's lifetime is tied to the app's, on a known event loop, from
the start:

```python
from contextlib import asynccontextmanager
from fastapi import FastAPI
import mind.embeddings

@asynccontextmanager
async def lifespan(app: FastAPI):
    await mind.embeddings.open()
    yield
    await mind.embeddings.close()

app = FastAPI(lifespan=lifespan)
```

If you never call `open()`, the store still initializes itself lazily on
first `upsert`/`search`/`delete`/`clear` -- fine for standalone scripts,
but for a long-running server prefer the explicit form above (see
`base.VectorStore.open()`'s docstring for why).

## Setup

Requires a local Postgres with the `vector` extension (build steps in
`pgvector`'s repo: https://github.com/pgvector/pgvector), and the `mind`
package installed with its normal dependencies:

```bash
make dev        # from teams/mind/ -- installs everything, including this module's deps
export MIND_VECTOR_STORE_DSN="dbname=mind user=postgres password=postgres host=localhost port=5432"
```

There's no hardcoded fallback for this DSN -- `config.py` fails fast at
import time if it isn't set, rather than silently connecting with
guessed/default credentials.

## Run the tests

```bash
pytest          # from teams/mind/ -- picks up tests/test_vector_store.py automatically
```

## Using the interface elsewhere in the app

```python
from mind.embeddings import embed, upsert, search, delete

vector = embed(task.title + " " + task.description)
await upsert(task.id, vector)

similar = await search(vector, k=5)  # [SearchResult(task_id=..., similarity=...), ...], best match first

await delete(task.id)  # e.g. when the task itself is deleted
```

No caller anywhere else in the codebase should import `pgvector_store.py`
directly, or know that Postgres is involved at all. If we switch databases
later, only `pgvector_store.py` + `factory.py` need to change.

## Known limitations (flag these in review)

- `embed()` uses a deterministic hashing-based text vectorizer, not a real
  semantic embedding model — see the note at the top of `embeddings.py` for
  why, and what to swap in. Quantified impact in
  `benchmarks/vector-store-comparison.md`'s "Validation" section: this is
  the dominant cause of near-zero recall on realistic near-duplicate
  detection at scale — not pgvector or its HNSW index.
- `DUPLICATE_SIMILARITY_THRESHOLD` in `config.py` is an untuned placeholder
  -- `search()` now returns a cosine similarity per result, but no caller
  applies the threshold to it yet.
