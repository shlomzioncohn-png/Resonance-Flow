# embeddings

Vectors, semantic search, duplicate/related-task detection.

## Decisions (closed)

- **Vector store: pgvector** — see [`vector-store-comparison.md`](../../../benchmarks/vector-store-comparison.md).
- **Embedding model: `BAAI/bge-small-en-v1.5`**, 384-dim, CPU-only, English-only. Asymmetric -- queries need `is_query=True`. Local weights optional via `MODEL_CACHE_DIR`.
- **Load target** (week 8, provisional): 10,000+ records, <300ms p95.

## What's here

```
__init__.py          public interface -- embed()/embed_batch() (+ async), SearchResult, VectorStore, nearest_tasks(), resolve_dependency_targets()
embeddings.py         embed() family -- the real model
base.py               VectorStore abstract contract + SearchResult
pgvector_store.py     the ONLY file that knows Postgres/pgvector
index_consumer.py     #470 -- Kafka task.{created,updated,completed} handlers
index_consumer_runner.py  #470 -- the process: python -m mind.embeddings.index_consumer_runner
search.py             nearest_tasks() -- the shared embed()+search() primitive behind #471/#245
dependency_resolution.py  #245 -- resolve_dependency_targets(): free-text dependency mentions -> ranked candidate task_ids
loader.py             fetches + verifies the real FUNSD manifest slice
ingest.py             loader -> embed_batch_async -> upsert
verify.py             table row count vs. manifest record count
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

## Real data: the FUNSD slice (not R2)

No R2 (Cloudflare object storage) loader/storage exists in this repo. `loader.py` fetches the real 2-document FUNSD slice (PR #141's manifest), verifies checksums, caches under `local-data/`.

```bash
python -m mind.embeddings.ingest    # fetch + verify + embed + upsert
python -m mind.embeddings.verify    # table row count vs. manifest
```

## Setup

```bash
make dev
export MIND_ENVIRONMENT=local
export MIND_VECTOR_STORE__DSN="postgresql://mind:mind@localhost:5432/mind"
```

`embed()` downloads `BAAI/bge-small-en-v1.5` on first use unless `MODEL_CACHE_DIR` points at a local copy.

## Run the tests

```bash
pytest
```

## Using the interface elsewhere in the app

```python
from mind.config import get_settings
from mind.embeddings import embed_async, task_text
from mind.embeddings.pgvector_store import PgVectorStore

store = PgVectorStore(get_settings().vector_store)

vector = await embed_async(task_text(task.title, task.description))  # is_query=False (default) for indexing
await store.upsert(task.id, vector)

similar = await store.search(vector, k=5)  # best match first
await store.delete(task.id)
```

For a query against stored passages (e.g. a search endpoint), use `store.search_text(query, k)` instead -- it embeds with `is_query=True` so the flag can't be forgotten.

`task_text()` is the canonical title+description join -- every producer must use it, or their vectors aren't comparable to each other.

`is_completed`, `upsert_if_absent()`, `mark_completed()` and `mark_incomplete()` back the #470 index consumer:

```python
await store.upsert_if_absent(task_id, vector)
await store.mark_completed(task_id)
await store.mark_incomplete(task_id)  # e.g. a DONE task got reopened
```

`nearest_tasks(store, text, *, k)` (`search.py`) is the shared primitive behind #471 and #245 -- `embed(text)` then `store.search(vector, k=k)`, no threshold.

`resolve_dependency_targets(store, references, *, k=5)` (`dependency_resolution.py`) is #245: a `list[str]` of free-text dependency mentions in, one ranked candidate list out per string. No match/no-match decision -- that's #244, on these same scores:

```python
from mind.embeddings import resolve_dependency_targets

candidates = await resolve_dependency_targets(store, ["the DB upgrade"])  # [[SearchResult(...), ...]]
```

## Known limitations (flag these in review)

- `Settings.duplicate_similarity_threshold` is an untuned placeholder -- no caller applies it yet.
- Real-data coverage is just the 2-document FUNSD slice (PR #141) -- enough to validate pipeline shape, not recall quality.
