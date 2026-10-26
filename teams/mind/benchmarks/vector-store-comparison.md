# MIND — Vector Database Comparison & Recommendation

## Purpose

This document compares vector database solutions for MIND, selects the most suitable option, and documents the reasoning behind the decision.

Candidates:
1. pgvector
2. Qdrant
3. OpenSearch k-NN
4. Milvus
5. Chroma / FAISS

---

# 1. Mandatory Requirements

| Requirement | What is required |
|---|---|
| Local | Must run locally with no cloud dependency |
| Open Source | Must be Open Source |
| No GPU | Must work without a GPU |
| Python | Must have a reliable Python library |
| Performance | Target of less than 300ms p95 |

The <300ms p95 target must be validated with a real benchmark. After implementation, at least 100 vectors will be used and the measured result will be recorded.

---

# 2. MIND's Existing Architecture

MIND already uses **PostgreSQL**.

pgvector allows Vector Search to be added to the existing infrastructure without introducing another database:

```text
MIND
  |
  v
PostgreSQL
  |
  +-- Tasks
  +-- Users
  +-- Projects
  +-- Embeddings
```

A separate solution such as Qdrant adds another storage system:

```text
MIND
  |
  +------------------+
  |                  |
  v                  v
PostgreSQL         Qdrant
  |                  |
Application data   Vectors
```

---

# 3. pgvector

## What is it?

pgvector is a PostgreSQL extension that adds Vector Similarity Search and allows embeddings to be stored inside PostgreSQL.

## Advantages

### PostgreSQL integration
Vectors can live with existing data without another database.

### Exact Search
Supports Exact Nearest Neighbor Search.

### Approximate Search
Supports indexes such as HNSW and IVFFlat.

### SQL Filtering
Vector Search can be combined with SQL conditions such as project, status, or owner.

### Operational simplicity
Using the existing PostgreSQL infrastructure reduces the number of components that must be operated and maintained.

## Disadvantages

### Filtering with Approximate Search
Combining Filtering with Approximate Search requires careful index and search-strategy design.

### Very large scale
At hundreds of millions of vectors, a dedicated vector database may provide broader vector-specific scaling and optimization capabilities.

## Fit for MIND

Especially suitable for MIND's current state because it adds the required vector capabilities to the existing PostgreSQL infrastructure.

---

# 4. Qdrant

## What is it?

Qdrant is a dedicated Vector Database built around vector storage and search.

```text
Vector + Payload / Metadata
              |
              v
            Qdrant
              |
              v
      Similarity Search
```

## Advantages

### Dedicated Vector Search
Designed specifically around vector search.

### Payload Filtering
Similarity Search can be combined with metadata filtering.

### Hybrid Search
Supports combining different search types.

### Quantization
Provides vector-compression options that can become important as vector count or embedding dimensions grow.

### Scale-out
Supports sharding and clustering for larger systems.

### Local Development
Can run locally without cloud dependency.

## Disadvantages

### Additional database
MIND already uses PostgreSQL, so Qdrant adds another system.

### Additional operations
Deployment, monitoring, backup, and maintenance are required for another component.

### Some advantages are not current requirements
Its strongest advantages are more relevant at large scale and with complex filtering, which are not current MIND bottlenecks.

## Fit for MIND

A strong candidate, especially if MIND grows significantly or needs more complex filtering. Currently, there is no clear need to add another database only for these capabilities.

---

# 5. OpenSearch k-NN

## What is it?

OpenSearch is a broad Search Engine with Vector Search and k-NN capabilities.

## Advantages

- Vector Search and k-NN
- Filtering
- Keyword + Vector Search
- Local and Open Source

## Disadvantages

- More complex than MIND's immediate needs
- Adds another Search Engine
- Does not use the existing PostgreSQL advantage like pgvector

## Fit for MIND

Useful if MIND eventually needs a broad Search platform combining keyword and vector search, but currently adds unnecessary complexity.

---

# 6. Milvus

## What is it?

Milvus is a dedicated Vector Database for AI systems and vector search.

## Advantages

- Dedicated Vector Database
- Scalability
- Python support
- Local Development
- Filtering and Hybrid Search

## Disadvantages

- May be more complex than currently required
- Adds a dedicated database instead of using existing PostgreSQL
- Adds operational overhead

## Fit for MIND

Technically strong, but currently it does not provide enough additional value to justify adding another system.

---

# 7. Chroma / FAISS

## What is it?

Chroma and FAISS are evaluated as the fifth candidate.

Chroma provides Vector/AI Data infrastructure with storage and search, while FAISS is a similarity-search library.

## Advantages

- Fast to start
- Good Python support
- Local usage
- Vector Search
- FAISS is particularly strong as a similarity-search engine

## Disadvantages

### FAISS is not a full database
Additional components are needed for metadata, persistence, and data management.

### Architectural fit
Chroma is convenient for prototyping, but pgvector integrates more naturally with MIND's existing PostgreSQL architecture.

## Fit for MIND

Useful for experiments and prototypes, but less suitable as the central infrastructure choice at this stage.

---

# 8. Comparison Table

**5 ⭐ = Excellent fit**  
**1 ⭐ = Relatively weak fit**

| Mandatory Criterion | pgvector | Qdrant | OpenSearch k-NN | Milvus | Chroma / FAISS |
|---|---:|---:|---:|---:|---:|
| Local / No Cloud | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Open Source | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Works without GPU | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Python Library | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Potential for <300ms p95 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Integration with existing PostgreSQL | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐ |
| Infrastructure complexity for MIND | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| Overall fit for MIND | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |

> **Performance note:** The latency rating is an initial assessment only. Actual compliance with <300ms p95 will be proven only through the benchmark with at least 100 vectors.

---

# 9. Decision Analysis

Qdrant, Milvus, and OpenSearch provide strong dedicated capabilities for vector search. They can be especially attractive when systems grow significantly, filtering becomes complex, or distributed vector infrastructure is required.

However, MIND already uses PostgreSQL.

pgvector therefore allows Vector Search to be added to the existing infrastructure without introducing another database.

At the current stage there is no requirement for hundreds of millions of vectors, complex multi-tenancy, or a dedicated distributed vector cluster. Therefore, there is currently no clear justification for adding another system to obtain capabilities that MIND does not yet require.

---

# 10. Final Decision

## Recommended choice: pgvector

**pgvector is the recommended choice for MIND at the current stage.**

The main reasons are:

1. PostgreSQL already exists in the system.
2. It provides the required Vector Search capabilities.
3. It supports Exact and Approximate Search.
4. It can be combined with SQL Filtering.
5. It runs locally without a GPU.
6. It works with Python.
7. It avoids adding another database.
8. Application data and embeddings can remain in the same infrastructure.

Qdrant remains the main alternative. If real usage shows that performance or filtering becomes a bottleneck, a Qdrant spike should be considered.

## Validation (done)

Theoretical comparison alone wasn't considered sufficient. A PoC validated,
in three stages, whether pgvector actually meets the requirements above:

| Validation | Question answered | Result |
|---|---|---|
| Latency benchmark (500 vectors) | Does the interface work at the minimum acceptance scale (100+ vectors)? | PASS — p95 well under 300ms |
| Semantic-quality evaluation (80-task corpus, 20 queries) | Does semantic search return the *right* results across realistic query types? | Partial — 42% avg top-5 precision with the placeholder embedding model; a real model raised this to 68% on the same test |
| Scale + recall evaluation (10,000-row load test, duplicate-pair recall, ANN-vs-exact recall) | Does pgvector meet the <300ms p95 / 10,000-vector goal **with recall measured, not just latency**? | **pgvector: meets it** — latency PASS with large headroom, and HNSW recall reaches 86% once `hnsw.ef_search` is tuned (applied in `src/mind/embeddings/pgvector_store.py`). **End-to-end: not yet** — 0% recall on realistic near-duplicate detection, traced to the placeholder embedding model, not pgvector or the index. |

**Net conclusion: pgvector stays the recommended choice.** Nothing found in
validation points at *query-time* HNSW tuning (`hnsw.ef_search`) as the
bottleneck — the gap was the embedding model. That gap is closed as of
the production module: `embeddings.py` now uses a real retrieval-tuned
model (`BAAI/bge-small-en-v1.5`), not a hashing placeholder — see
`src/mind/embeddings/README.md`. This does **not** yet rule out
*build-time* HNSW quality (`hnsw.ef_construction`, and whether the index
is built before or after the data load) — that wasn't tested; see the
open follow-up issue below.

The full PoC-era evaluation (mock corpus, query sets, recall test
scripts, and the detailed writeups) lived at `mind_vector_poc/evaluation/`.
The two summary reports are copied into this directory —
[`SCALE_AND_RECALL_REPORT.md`](SCALE_AND_RECALL_REPORT.md) and
[`BENCHMARK_REPORT.md`](BENCHMARK_REPORT.md) — since the PoC PR (#54)
won't be merged and its branch history won't be part of `main`. The PoC
folder itself was removed once its logic was promoted into
`src/mind/embeddings/`. See "Benchmark results" below for the follow-up
validation against the real model and production code.

---

## Benchmark results

The validation above still used the PoC and its hashing-based placeholder
embedding model. This re-runs the same kind of test against the actual
**production code** (`src/mind/embeddings`) and the actual **real
embedding model** (`BAAI/bge-small-en-v1.5`), addressing issue #87. Script:
[`vector_search_benchmark.py`](vector_search_benchmark.py) (raw results:
`vector_search_benchmark_results.json`, gitignored — regenerate by
re-running the script).

### Dataset

| Kind | Count | id prefix | What it is |
|---|---:|---|---|
| Synthetic filler | 10,000 | `filler-` | Random word-soup, padding to a realistic load scale — **not real data** |
| Manually-selected near-duplicate pairs | 16 (8 pairs) | `pair-#-a` / `pair-#-b` | Same underlying problem, different wording, for the recall test |
| **Total** | **10,016** | | |

### Latency — 100 similarity queries, `search()` only (embedding time excluded, same convention as the earlier PoC benchmarks)

| avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---:|---:|---:|---:|---:|
| 14.07 | 14.58 | 16.06 | 16.70 | 16.97 |

**<300ms p95 target: PASS**, with roughly 18.7x headroom at 10,000 rows.
Latency is dominated by the fixed cost of an HNSW scan at this scale, not
by anything the real embedding model changed — consistent with the
earlier PoC finding that pgvector itself was never the bottleneck.

### Recall — does searching one pair member surface the other?

| k | Recall |
|---|---:|
| 5 | **87.5%** (7/8) |
| 10 | **87.5%** (7/8) |

This uses BGE's query/passage asymmetry correctly (the query side is
embedded with `is_query=True`, i.e. `embeddings.BGE_QUERY_INSTRUCTION`
prepended, matching how a real search call would work) — the earlier PoC
recall numbers (42% avg top-5 precision, then 0% on the harder
duplicate-pair test) were measured against the old hashing placeholder,
which had no notion of meaning at all. The one miss ("Customer reported
duplicate invoices being generated" → "Client says the billing system is
creating the same invoice twice") shares almost no literal words between
the two phrasings — a genuinely hard paraphrase case even for a small
retrieval model, not a sign of a broken pipeline.

### Explicit conclusion

**<300ms p95 is comfortably achievable at the target scale** (10,000+
rows) — measured p95 is 16.06ms, not an estimate. Latency has ~18x
headroom before <300ms would even become a concern, and the one recall
miss is attributable to the embedding model's inherent limits on a hard
paraphrase, not to index accuracy.

**Resolved by #475 below:** review flagged (via `EXPLAIN`) that at this
10,000-row scale, with `hnsw.ef_search = 400`, Postgres's planner may
choose a sequential scan over the HNSW index for this query shape. Confirmed
with a real `EXPLAIN ANALYZE` against a 10,000-row table (see "Live-path
latency under load" below): the planner does use the HNSW index at this
scale -- `Index Scan using ..._embedding_hnsw_idx`, not a seq scan. (At a
much smaller scale, ~50 rows, it does choose a bitmap/seq scan instead --
expected, and not a concern at the 10,000-row target.)

## Foundation Gate benchmark (week 3)

The three validation stages above all used synthetic or placeholder data
-- real data was explicitly BLOCKED at the time (no loader existed, see
`SCALE_AND_RECALL_REPORT.md` §1). That has changed: `eval/real_slice/`
now has 16 real, human-annotated tasks (verbatim AMI Meeting Corpus
action items, three real meetings -- see each file's `source` field) and
the vector-store interface is final. [`foundation_gate_benchmark.py`](foundation_gate_benchmark.py)
re-runs the benchmark against this real slice, run via:

```bash
cd teams/mind
docker compose up -d vector-store
source .venv/bin/activate   # or: make dev, then activate
export MIND_VECTOR_STORE__DSN="postgresql://mind:mind@localhost:5432/mind"
python -m benchmarks.foundation_gate_benchmark
```

Two different things are measured, deliberately kept separate:

- **Latency (p50/p95/p99)** needs scale, not realism. The 16 real tasks
  are padded with 84 synthetic filler rows to reach this doc's
  >=100-vector minimum (§1) -- filler only ever affects scale, never the
  recall measurement below.
- **Recall** needs realism, not scale. There's no real near-duplicate
  corpus in this data (no two AMI action items are paraphrases of each
  other), so recall here asks a different, equally real question: for
  three hand-identified task pairs that discuss the same sub-topic
  within one real meeting (e.g. two action items both about collecting
  seating preferences), does the related real task outrank an unrelated
  real control task from the *same* meeting?

**Result below is STALE** (review feedback on #559, 2026-10-12): it's
from a run against `embeddings.py`'s old HashingVectorizer placeholder,
before the real BGE model landed. The latency numbers also predate a
fix -- the timer used to start before `embed(text)`, so it measured
model inference and `search()` together. Both are fixed in code now
(real model, timer moved past `embed()`); the table itself hasn't been
re-run since -- needs a fresh `python -m benchmarks.foundation_gate_benchmark`
against current `main` for real numbers.

| Metric | Result (stale -- placeholder model) |
|---|---|
| Total vectors (16 real + 84 synthetic filler) | 100 |
| Latency p50 / p95 / p99 | 3.73ms / 15.72ms / 21.84ms |
| Recall (related outranks control, real pairs) | **3/3** |

p99 is ~14x under the 300ms target, consistent with the earlier
10,000-row synthetic result -- latency doesn't depend on content, only
vector count and dimension, so this isn't a surprise. The real finding
is the recall column: unlike the semantic-quality validation above
(0% end-to-end recall on paraphrased near-duplicates, traced to the
placeholder embedding model), plain topical relatedness within a real
meeting ranks correctly 3/3 here. **This does not contradict the earlier
finding** -- "the same sub-topic, different wording" (what these three
pairs test) and "a near-exact paraphrase" (what the semantic-quality
stage tested) are different difficulty levels for a word-overlap
embedding model; the gap was in `embeddings.py`'s placeholder model
(now replaced), not pgvector or its HNSW index.

**HNSW confirmed in code, not just planned:** `pgvector_store.py`'s
`_ensure_schema()` already creates a `USING hnsw (embedding
vector_cosine_ops)` index (`CREATE INDEX IF NOT EXISTS ...`), and
`search()` sets `hnsw.ef_search` before every query -- this benchmark
ran against that real code path, not a bypass.

## Follow-up (open, not fixed here)

HNSW *build-time* quality was never investigated — only `ef_search`
(query-time) was tuned. Two things are still untested: the default
`ef_construction=64` (pgvector's docs suggest higher values, e.g. 200, can
improve recall for a given `ef_search`), and whether building the index
before vs. after loading the 10,000-row dataset changes recall. Tracked
as [issue #270](https://github.com/KamaTechOrg/Resonance-Flow/issues/270)
rather than fixed in this PR, since it needs its own benchmark run to
answer.

The benchmark still uses synthetic filler data (random word-soup), not
real task text -- the original issue asked for the real FUNSD dataset.
Not switched over in this PR; flagging rather than fixing, since it
changes what the recall numbers above actually measure.

## Live-path latency under load (#475)

The two validations above both measure `PgTenantStore.search()` directly.
That skips two round trips the live endpoint (`GET /v1/tasks/{task_id}/related`,
#472) actually makes before `search()` even runs: `VectorIndex.tenant_of()`
and `VectorStore.get_vector()`. Neither run under concurrency, and neither
has writes happening at the same time the real index consumer would be
making them. Measuring an isolated, read-only, single-threaded call to
`search()` alone is measuring a faster path than production ships --
exactly what #475 asks not to do. This re-measures through the actual
live path, under conditions closer to production.

Script: [`related_tasks_live_path_benchmark.py`](related_tasks_live_path_benchmark.py)
(raw results: `related_tasks_live_path_benchmark_results.json`, gitignored
-- regenerate by re-running the script). Its own table
(`task_embeddings_benchmark_475`), dropped after the run.

### What is actually measured

Each of the 300 measured calls times exactly one invocation of
`mind.api.v1.router.get_related_tasks(task_id, index)` -- the literal
function FastAPI calls for that route, not a hand-written query. One call
is 5 round trips to Postgres: `tenant_of()` (1), `get_vector()` (1), then
`search()`'s own 3 (two `SET` statements for `hnsw.ef_search` /
`hnsw.iterative_scan`, then the `ORDER BY embedding <=> ... LIMIT k`
itself) -- plus Python-side threshold filtering, no further I/O. No
embedding happens in this path: `get_related_tasks` starts from a task's
*already-stored* vector, not fresh text.

Not driven through a running HTTP server or ASGI transport: the app's own
lifespan (`api/main.py`) also starts Kafka consumers and the score
publisher, neither of which bears on vector-search latency, and both of
which would make this benchmark need a Kafka broker just to start. Calling
the router function directly runs the exact same code the live endpoint
runs; the one thing skipped is the loopback socket hop into uvicorn,
whose overhead for a local call is itself sub-millisecond.

### Dataset: 10,000 rows, 0 real, 100% synthetic

No real Mind task corpus exists at this scale to load, so every row is
synthetic -- stated plainly, not glossed over. Not random word-soup
either (gibberish or near-identical strings cluster in embedding space in
ways real task text never does, which would make a latency number
measured against them not representative -- flagged in review): row text
comes from [`synthetic_task_seeds.py`](synthetic_task_seeds.py), built on
realistic task descriptions an LLM (Gemini) wrote, saved under
[`synthetic_task_seeds_raw/`](synthetic_task_seeds_raw/) (committed --
see `.gitignore`'s exception for it). Currently 1,647 unique seed entries
spanning several work domains (software engineering/bug triage, sales,
finance/procurement, HR/payroll, facilities, compliance, logistics,
customer support, events), not just one.

1,647 seeds don't reach 10,000 rows on their own, so `expand_to()` cycles
through a fixed shuffle of them, reusing each about 6 times. The first
use of each seed is exactly what the LLM wrote; every reuse after that is
re-textured -- a different name, date, ticket id, number, and (since most
titles contain none of those) an occasional leading-verb synonym swap and
bracketed tag -- so reusing the corpus several times over doesn't itself
produce near-duplicate rows. Checked, not assumed: of the 10,000 expanded
rows, 8,148 have a unique title and 9,727 have a unique full text.
Deterministic (`seed=475` by default): anyone re-running `expand_to(10_000)`
gets the same 10,000 rows.

### Conditions

- **Warm**: 20 queries run and are discarded before measurement starts.
- **Concurrent**: 20 `get_related_tasks()` calls in flight at once
  (`asyncio.Semaphore`), not one request at a time.
- **Writes in flight**: a background task continuously upserts rows for
  the whole measured window, the way the real index consumer would.
  Confirmed, not assumed to have run: the result records
  `writes_during_measurement` (50 in the run below) -- a real count, not
  zero.
- **Varied queries**: each of the 300 measured calls targets a different,
  randomly chosen task_id out of the 10,000 loaded -- not one query
  repeated, which would measure a cache rather than the index.

### Results (10,000 rows, 1,647-seed multi-domain corpus)

| avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---:|---:|---:|---:|---:|
| 123.90 | 120.83 | 161.45 | 189.45 | 200.39 |

**<300ms p95 target: PASS**, with roughly 139ms (46%) of headroom left --
comfortable, but nowhere near the ~18x headroom the isolated `search()`-only
number above suggested. The full live path (5 round trips, 20-way
concurrency, a writer competing for the same connection pool) costs
roughly 10x what an isolated `search()` call does; the earlier number was
real but was never the thing #475 asks to prove.

**HNSW confirmed in use at this scale**, not assumed: `EXPLAIN (ANALYZE,
FORMAT TEXT)` against the live 10,000-row table returns
`Index Scan using task_embeddings_benchmark_475_embedding_hnsw_idx`, not
a sequential or bitmap scan -- resolving the open question flagged above.
(At a much smaller scale tested during development, ~50 rows, the planner
instead chose a bitmap scan over the tenant_id index -- the right call at
that size, and not a concern at the 10,000-row target.)

### Breaking point and diagnosis

Not explored past 10,000 rows in this pass: the target is comfortably met
(46% headroom), and with only one scale tested there is no second data
point yet to extrapolate a breaking point from. Where the time goes at
10,000 rows is not a mystery, though -- it is the 5 round trips per call
above, under contention from concurrency and a competing writer, not the
vector search itself (the HNSW scan alone, per `EXPLAIN ANALYZE`, is
single-digit milliseconds). A later pass that re-runs this at 50,000 and
100,000 rows would show whether that per-call cost grows with table size
or stays flat (HNSW's own query cost is expected to scale roughly
`O(log n)`, so the round-trip/contention overhead, not the index, is the
more likely bottleneck as scale grows) -- not done here to keep this PR's
scope to what #475 asks for at the stated floor.

### Known limitations

- **The seed corpus is multi-domain but still far smaller than 10,000**:
  1,647 unique scenarios, each reused ~6 times with re-texturing. More
  domains narrow the gap further but do not close it completely; a
  production corpus this size would have far more independent scenarios.
- **The `EXPLAIN ANALYZE` plan was captured with a fixed, synthetic query
  vector** (not a literal replay of one of the 300 measured queries) --
  representative of the same query shape and scale, not a replay.
- **Connection-pool size (25) and concurrency (20) are this benchmark's
  own choices**, not derived from a measured production concurrency
  profile, which does not exist yet to measure.

---
