"""
Verification: row count in the vector-store table == record count in the
manifest that was ingested.

Run:  python -m mind.embeddings.verify
Exits 0 on match, 1 on mismatch (so it's usable as a CI gate).
"""

import sys

import psycopg
from psycopg import sql

from mind.embeddings.config import get_settings
from mind.embeddings.loader import MANIFEST_PATH, _load_manifest


def main() -> int:
    dsn = get_settings().vector_store_dsn
    if not dsn:
        raise RuntimeError("MIND_VECTOR_STORE_DSN is not set. Required to connect to the vector store.")

    manifest_count = len(_load_manifest()["records"])

    # Plain sync psycopg here (not the async pool pgvector_store.py uses)
    # -- this is a one-off CLI check, not a request-serving hot path, so
    # there's no event loop to protect.
    with psycopg.connect(dsn) as conn, conn.cursor() as cur:
        cur.execute(
            sql.SQL("SELECT COUNT(*) FROM {table};").format(
                table=sql.Identifier(get_settings().vector_store_table)
            )
        )
        row = cur.fetchone()
        assert row is not None, "SELECT COUNT(*) always returns exactly one row"
        table_count = row[0]

    print(f"Manifest ({MANIFEST_PATH.name}): {manifest_count} records")
    print(f"Table row count:                {table_count} rows")

    if table_count == manifest_count:
        print("PASS: table row count matches manifest record count.")
        return 0

    print("FAIL: table row count does not match manifest record count.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
