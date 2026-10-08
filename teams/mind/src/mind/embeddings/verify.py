"""
Verification: every manifest record that was ingested actually has a row
in the vector-store table.

Run:  python -m mind.embeddings.verify
Exits 0 on match, 1 on mismatch (so it's usable as a CI gate).
"""

import sys

import psycopg
from psycopg import sql

from mind.config import get_settings
from mind.embeddings.loader import MANIFEST_PATH, _load_manifest


def main() -> int:
    settings = get_settings().vector_store
    dsn = str(settings.dsn.get_secret_value())
    manifest_ids = [record["id"] for record in _load_manifest()["records"]]

    # Plain sync psycopg here (not the async pool pgvector_store.py uses)
    # -- this is a one-off CLI check, not a request-serving hot path, so
    # there's no event loop to protect.
    #
    # Count only the manifest's ids -- `table` is the real task index too,
    # so a bare COUNT(*) breaks once any real task is indexed.
    with psycopg.connect(dsn) as conn, conn.cursor() as cur:
        cur.execute(
            sql.SQL("SELECT COUNT(*) FROM {table} WHERE task_id = ANY(%s);").format(
                table=sql.Identifier(settings.table)
            ),
            (manifest_ids,),
        )
        row = cur.fetchone()
        assert row is not None, "SELECT COUNT(*) always returns exactly one row"
        table_count = row[0]

    print(f"Manifest ({MANIFEST_PATH.name}): {len(manifest_ids)} records")
    print(f"Matching rows in table:         {table_count} rows")

    if table_count == len(manifest_ids):
        print("PASS: every manifest record has a row in the table.")
        return 0

    print("FAIL: not every manifest record has a row in the table.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
