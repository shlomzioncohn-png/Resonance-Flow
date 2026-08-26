# embeddings

Vectors, semantic search, duplicate detection.

- Vector-store client + indexing (candidates: pgvector, Qdrant, OpenSearch k-NN, Milvus, Chroma/FAISS — see open decisions).
- Duplicate / related-task detection via nearest-neighbor search.
- Load target (week 8, provisional): 10,000+ embedded tasks, semantic query <300ms p95.
