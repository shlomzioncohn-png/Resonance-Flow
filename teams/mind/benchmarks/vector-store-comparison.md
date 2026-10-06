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

**Net conclusion: pgvector stays the recommended choice for the read/write
path validated here.** Nothing found in validation points at *query-time*
HNSW tuning (`hnsw.ef_search`) as the bottleneck — the dominant gap is the
embedding model (`embeddings.py` still uses a hashing-based placeholder,
deliberately not swapped for a real semantic model yet). This does **not**
yet rule out *build-time* HNSW quality (`hnsw.ef_construction`, and
whether the index is built before or after the data load) — that wasn't
tested; see the open follow-up issue below.

The full evaluation (mock corpus, query sets, recall test scripts, and
the detailed writeups) lived in the PoC at `mind_vector_poc/evaluation/`.
The two summary reports are copied into this directory —
[`SCALE_AND_RECALL_REPORT.md`](SCALE_AND_RECALL_REPORT.md) and
[`BENCHMARK_REPORT.md`](BENCHMARK_REPORT.md) — since the PoC PR (#54)
won't be merged and its branch history won't be part of `main`. The PoC
folder itself was removed once its logic was promoted into
`src/mind/embeddings/`.

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

**Result (last real run, [`foundation_gate_benchmark_results.json`](foundation_gate_benchmark_results.json)):**

| Metric | Result |
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
embedding model; the gap is still in `embeddings.py`'s placeholder
model, not pgvector or its HNSW index.

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

---
