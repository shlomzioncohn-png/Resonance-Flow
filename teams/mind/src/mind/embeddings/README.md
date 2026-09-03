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
__init__.py         the public interface -- embed() / upsert() / search() / delete()
embeddings.py       embed() -- the (placeholder) text vectorizer
base.py             VectorStore abstract interface (the "contract") -- dependency-free
pgvector_store.py   the ONLY file that knows about Postgres/pgvector
factory.py          single switch that picks the backend implementation
config.py           connection settings, table name, HNSW tuning, duplicate-detection threshold
```

Nothing outside `__init__.py` should be imported by callers elsewhere in
the app. `base.py` deliberately has zero backend-specific dependencies
(only `abc`) so the interface itself can be imported -- for type-checking,
mocking in tests, or sketching a new backend -- without requiring
psycopg2 to be installed; only `pgvector_store.py` (reached through
`factory.py`) actually imports it.

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
pytest          # from teams/mind/ -- picks up tests/test_vector_store.py automatically
```

## Using the interface elsewhere in the app

```python
from mind.embeddings import embed, upsert, search, delete

vector = embed(task.title + " " + task.description)
upsert(task.id, vector)

similar_task_ids = search(vector, k=5)

delete(task.id)  # e.g. when the task itself is deleted
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
- `DUPLICATE_SIMILARITY_THRESHOLD` in `config.py` is an untuned placeholder,
  and `search()` doesn't return a similarity score to apply it against yet.
