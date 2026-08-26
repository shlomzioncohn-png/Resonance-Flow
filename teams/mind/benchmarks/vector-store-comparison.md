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
gap is the embedding model (`embeddings.py` still uses a hashing-based
placeholder, deliberately not swapped for a real semantic model yet). The
full evaluation (mock corpus, query sets, recall test scripts, and the
detailed writeups) lived in the PoC at `mind_vector_poc/evaluation/` and
is preserved in git history / PR #54, not in the working tree — the PoC
folder itself was removed once its logic was promoted into
`src/mind/embeddings/`.

---
