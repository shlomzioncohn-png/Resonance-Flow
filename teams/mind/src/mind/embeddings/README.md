# embeddings

Vectors, semantic search, duplicate/related-task detection.

## Decisions (closed)

- **Vector store: pgvector** — see [`vector-store-comparison.md`](../../../benchmarks/vector-store-comparison.md).
- **Embedding model: still a placeholder** (`embeddings.py`, HashingVectorizer) -- swapping it is a one-function change, tracked separately.
- **Load target** (week 8, provisional): 10,000+ records, <300ms p95.

## What's here

```
__init__.py          public interface -- embed(), SearchResult, VectorStore, nearest_tasks(), resolve_dependency_targets()
embeddings.py         embed() -- the placeholder text vectorizer
base.py               VectorStore abstract contract + SearchResult
pgvector_store.py     the ONLY file that knows Postgres/pgvector
index_consumer.py     #470 -- Kafka task.{created,updated,completed} handlers (see scripts/index_consumer.py for the process)
search.py             nearest_tasks() -- the shared embed()+search() primitive behind #471/#245
dependency_resolution.py  #245 -- resolve_dependency_targets(): free-text dependency mentions -> ranked candidate task_ids
```

Config lives in `mind.config`. Only `PgVectorStore` is constructed directly outside `__init__.py` -- no factory/singleton.

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
```

## Setup

```bash
make dev
export MIND_ENVIRONMENT=local
export MIND_VECTOR_STORE__DSN="postgresql://mind:mind@localhost:5432/mind"
```

## Run the tests

```bash
pytest
```

## Using the interface elsewhere in the app

```python
from mind.config import get_settings
from mind.embeddings import embed
from mind.embeddings.pgvector_store import PgVectorStore

store = PgVectorStore(get_settings().vector_store)

vector = embed(task.title + " " + task.description)
await store.upsert(task.id, vector)

similar = await store.search(vector, k=5)  # best match first
await store.delete(task.id)
```

`is_completed`, `upsert_if_absent()` and `mark_completed()` back the #470 index consumer:

```python
await store.upsert_if_absent(task_id, vector)
await store.mark_completed(task_id)
```

`nearest_tasks(store, text, *, k)` (`search.py`) is the shared primitive behind #471 and #245 -- `embed(text)` then `store.search(vector, k=k)`, no threshold.

`resolve_dependency_targets(store, references, *, k=5)` (`dependency_resolution.py`) is #245: a `list[str]` of free-text dependency mentions in, one ranked candidate list out per string. No match/no-match decision -- that's #244, on these same scores:

```python
from mind.embeddings import resolve_dependency_targets

candidates = await resolve_dependency_targets(store, ["the DB upgrade"])  # [[SearchResult(...), ...]]
```

## Known limitations (flag these in review)

- `embed()` is a hashing placeholder, not a real semantic model -- dominant cause of near-zero recall on near-duplicates at scale.
- `Settings.duplicate_similarity_threshold` is an untuned placeholder -- no caller applies it yet.
