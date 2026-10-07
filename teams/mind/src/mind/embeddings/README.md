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
__init__.py         the public interface -- embed(), SearchResult, VectorStore, nearest_tasks()
embeddings.py        embed() -- the (placeholder) text vectorizer
base.py               VectorStore abstract interface (the "contract") + SearchResult -- dependency-free (besides pydantic)
pgvector_store.py    the ONLY file that knows about Postgres/pgvector
index_consumer.py    #470 -- Kafka task.{created,updated,completed} handlers that keep the store in sync (see ../../../scripts/index_consumer.py for the process that runs them)
search.py             nearest_tasks() -- the shared embed()+search() primitive behind #471/#245
dependency_resolution.py  #245 -- resolve_dependency_targets(): Yonit's free-text dependency mentions -> ranked candidate task_ids
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
`mind.config.get_settings()`) does.

## Run the tests

```bash
pytest          # from teams/mind/ -- picks up tests/test_vector_store.py automatically
```

## Using the interface elsewhere in the app

```python
from mind.config import get_settings
from mind.embeddings import embed
from mind.embeddings.pgvector_store import PgVectorStore

store = PgVectorStore(get_settings().vector_store)  # or reuse app.state.vector_store

vector = embed(task.title + " " + task.description)
await store.upsert(task.id, vector)

similar = await store.search(vector, k=5)  # [SearchResult(task_id=..., similarity=...), ...], best match first

await store.delete(task.id)  # e.g. when the task itself is deleted
```

`SearchResult.is_completed` and the `upsert_if_absent()`/`mark_completed()`
primitives exist for the #470 index consumer (`index_consumer.py`), which
keeps this store in sync with Inbox's `task.created`/`task.updated`/
`task.completed` Kafka events:

```python
await store.upsert_if_absent(task_id, vector)  # insert-or-no-op, not upsert's insert-or-update
await store.mark_completed(task_id)             # True if a row existed and was updated
```

`nearest_tasks(store, text, *, k)` (`search.py`) is the shared primitive
behind duplicate detection (#471) and dependency-target resolution (#245):
`embed(text)` then `store.search(vector, k=k)`, nothing else -- callers
apply their own similarity threshold to the result.

`resolve_dependency_targets(store, references, *, k=5)`
(`dependency_resolution.py`) is #245 itself: given Yonit's extracted
free-text dependency mentions (`list[str]`, one string per mention --
the interface agreed with her), returns one ranked candidate-task list
per reference, same order. Makes no match/no-match decision -- that's
#244's confidence scoring, on top of these same similarity scores:

```python
from mind.embeddings import resolve_dependency_targets

candidates = await resolve_dependency_targets(store, ["the DB upgrade"])  # [[SearchResult(...), ...]]
```

No caller anywhere else in the codebase should know that Postgres is
involved. If we switch databases later, only `pgvector_store.py` (a new
sibling implementing `base.VectorStore`) needs to change.

## Known limitations (flag these in review)

- `embed()` uses a deterministic hashing-based text vectorizer, not a real
  semantic embedding model — see the note at the top of `embeddings.py` for
  why, and what to swap in. Quantified impact in
  `benchmarks/vector-store-comparison.md`'s "Validation" section: this is
  the dominant cause of near-zero recall on realistic near-duplicate
  detection at scale — not pgvector or its HNSW index.
- `Settings.duplicate_similarity_threshold` (`mind.config`) is an untuned
  placeholder -- `search()` now returns a cosine similarity per result,
  but no caller applies the threshold to it yet.
