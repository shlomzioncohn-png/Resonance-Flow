"""
Verification: row count in the vector-store table == record count in the
manifest that was ingested.

Run:  python -m mind.embeddings.verify
Exits 0 on match, 1 on mismatch (so it's usable as a CI gate).
"""

import sys

import psycopg2

from mind.embeddings.config import PG_DSN, PG_TABLE
from mind.embeddings.loader import MANIFEST_PATH, _load_manifest


def main() -> int:
    manifest_count = len(_load_manifest()["records"])

    conn = psycopg2.connect(PG_DSN)
    with conn, conn.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM {PG_TABLE};")
        table_count = cur.fetchone()[0]
    conn.close()

    print(f"Manifest ({MANIFEST_PATH.name}): {manifest_count} records")
    print(f"Table row count:                {table_count} rows")

    if table_count == manifest_count:
        print("PASS: table row count matches manifest record count.")
        return 0

    print("FAIL: table row count does not match manifest record count.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
