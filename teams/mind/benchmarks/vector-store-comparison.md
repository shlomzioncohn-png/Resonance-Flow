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
validation points at pgvector or its HNSW index as the bottleneck — the
gap was the embedding model. That gap is closed as of the production
module: `generate.py` now uses a real retrieval-tuned model
(`BAAI/bge-small-en-v1.5`), not a hashing placeholder — see
`src/mind/embeddings/README.md`. The full PoC-era evaluation (mock corpus,
query sets, recall test scripts, and the detailed writeups) lived at
`mind_vector_poc/evaluation/` and is preserved in git history / PR #54,
not in the working tree — the PoC folder itself was removed once its
logic was promoted into `src/mind/embeddings/`. See "Benchmark results"
below for the follow-up validation against the real model and production
code.

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
embedded with `is_query=True`, i.e. `config.BGE_QUERY_INSTRUCTION`
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
rows) — measured p95 is 16.06ms, not an estimate. **No index change is
needed or was made.** The HNSW index + `hnsw.ef_search = 400` tuning
already in `pgvector_store.py` (from the earlier PoC recall work) is
sufficient; latency has ~18x headroom before <300ms would even become a
concern, and the one recall miss is attributable to the embedding model's
inherent limits on a hard paraphrase, not to index accuracy. Per the
issue's instruction, no index was added or modified, and none is
recommended at this scale.

---
