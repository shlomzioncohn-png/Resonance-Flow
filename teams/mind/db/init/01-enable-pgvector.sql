-- Runs automatically on the vector-store container's first startup
-- (mounted into /docker-entrypoint-initdb.d, Postgres's own init-script
-- convention -- only runs against an empty data directory).
--
-- Enables the pgvector extension so it's ready for the real embeddings
-- work; no application tables yet, those land with that feature.
CREATE EXTENSION IF NOT EXISTS vector;
