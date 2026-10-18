"""Loads a generated dataset into a Postgres that has Chain's real history-table schema.

The schema is not re-declared here: Chain's own Flyway files (V2, V7, V9) are applied as they are, so
Postgres itself enforces the CHECK constraints and unique index on our synthetic rows. Never point
this at the shared Chain database; it refuses to load into tables that already hold rows.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import psycopg

DEFAULT_DSN = "postgresql://health:health@localhost:55432/chain_health"
MIGRATIONS = ("V2__init_dependency_history.sql", "V7__add_dependency_history_rejected_action.sql",
              "V9__init_task_state_history.sql")
TABLES = {
    "dependency_history": ("id", "task_id", "depends_on_task_id", "action", "source", "source_ref",
                           "created_at", "sequence"),
    "task_state_history": ("id", "task_id", "event_type", "from_status", "to_status",
                           "blocking_count_before", "blocking_count_after", "created_at", "sequence"),
}


def _migrations_dir() -> Path:
    override = os.environ.get("CHAIN_MIGRATIONS_DIR")
    if override:
        return Path(override)
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "src" / "main" / "resources" / "db" / "migration"
        if candidate.is_dir():
            return candidate  # teams/chain/src/main/resources/db/migration
    raise FileNotFoundError("Chain's migrations not found; set CHAIN_MIGRATIONS_DIR")


def ensure_schema(conn: psycopg.Connection) -> None:
    present = [conn.execute("SELECT to_regclass(%s)", (t,)).fetchone()[0] is not None for t in TABLES]
    if all(present):
        return
    if any(present):
        raise RuntimeError("only one of the history tables exists; start from a fresh database")
    for name in MIGRATIONS:
        conn.execute((_migrations_dir() / name).read_text(encoding="utf-8"))


def load(dsn: str, data_dir: Path) -> dict[str, int]:
    """Copies the dataset's CSVs in; returns row counts. Raises if the tables are not empty."""
    counts = {}
    with psycopg.connect(dsn) as conn:
        ensure_schema(conn)
        for table, columns in TABLES.items():
            if conn.execute(f"SELECT EXISTS (SELECT 1 FROM {table})").fetchone()[0]:
                raise RuntimeError(f"{table} already has rows; use a fresh database (docker compose down)")
            cols = ", ".join(columns)
            with conn.cursor() as cur, (data_dir / f"{table}.csv").open("rb") as f:
                with cur.copy(f"COPY {table} ({cols}) FROM STDIN WITH (FORMAT csv, HEADER true)") as copy:
                    while chunk := f.read(1 << 16):
                        copy.write(chunk)
            # we supplied explicit sequence values, so move the BIGSERIAL past them
            conn.execute(f"SELECT setval(pg_get_serial_sequence('{table}', 'sequence'), "
                         f"(SELECT max(sequence) FROM {table}))")
            counts[table] = conn.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
    return counts


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m health.loader", description=__doc__.split("\n\n")[0])
    p.add_argument("--data", type=Path, default=Path("out/dev"), help="dataset directory (default: out/dev)")
    p.add_argument("--dsn", default=os.environ.get("HEALTH_DB_DSN", DEFAULT_DSN))
    args = p.parse_args(argv)
    try:
        counts = load(args.dsn, args.data)
    except (RuntimeError, psycopg.Error, FileNotFoundError) as e:
        print(f"load failed: {e}", file=sys.stderr)
        return 1
    print("loaded " + ", ".join(f"{t}: {n} rows" for t, n in counts.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
