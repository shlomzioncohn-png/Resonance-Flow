"""The only writer of Chain's `flow_health_flag` table: loads one run's flags.json for one tenant.

    python -m flow_health.publish --flags out/<name>/flags.json --tenant <tenant uuid>

Each run analyses one tenant's data, so the file carries that tenant (`meta.tenant_id`, set with
`--tenant` on the detectors or on run_all). Publishing replaces that tenant's flags from the previous run, in
one transaction, and never touches another tenant's rows. A recommendation only: nothing here changes tasks
or dependencies.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from pathlib import Path
from typing import Any

from .detectors.common import PATTERNS, SEVERITIES, Flag, rank_order

DSN_ENV = "FLOW_HEALTH_DATABASE_URL"

DELETE_SQL = "DELETE FROM flow_health_flag WHERE tenant_id = %s"
INSERT_SQL = (
    "INSERT INTO flow_health_flag "
    "(tenant_id, flag_id, pattern, entity_type, entity_ids, severity, score, rank, explanation, evidence, run_at) "
    "VALUES (%s, %s, %s, %s, %s::jsonb, %s, %s, %s, %s, %s::jsonb, %s::timestamptz)"
)


def resolve_tenant(payload: dict, override: str | None = None) -> str:
    """The tenant of this run: `meta.tenant_id`, which `override` (the --tenant option) may only repeat."""
    in_file = payload.get("meta", {}).get("tenant_id")
    if override and in_file and _uuid(override) != _uuid(in_file):
        raise ValueError(f"--tenant {override} does not match the file's meta.tenant_id {in_file}")
    tenant = override or in_file
    if not tenant:
        raise ValueError("no tenant: pass --tenant, or run the detectors with --tenant so meta.tenant_id is set")
    return _uuid(tenant)


def build_rows(payload: dict, tenant_id: str) -> list[tuple[Any, ...]]:
    """One row per flag, in the table's column order. `rank` is the overall order (severity, then score)."""
    as_of = payload.get("meta", {}).get("as_of")
    if not as_of:
        raise ValueError("flags.json has no meta.as_of, so the run date of the flags is unknown")
    flags = [Flag(**f) for f in payload["flags"]]
    unknown = sorted({f"{f.flag_id} ({f.pattern}, {f.severity})" for f in flags
                      if f.pattern not in PATTERNS or f.severity not in SEVERITIES})
    if unknown:
        raise ValueError(f"unknown pattern or severity: {', '.join(unknown)}")
    ids = [f.flag_id for f in flags]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        raise ValueError(f"flag_id is not unique in the file: {', '.join(duplicates)}")
    ordered = rank_order(flags)
    rank_of = {f.flag_id: i for i, f in enumerate(ordered, start=1)}
    return [(tenant_id, f.flag_id, f.pattern, f.entity_type, json.dumps(f.entity_ids), f.severity, f.score,
             rank_of[f.flag_id], f.explanation, json.dumps(f.evidence, ensure_ascii=False), as_of)
            for f in ordered]


def publish(conn, payload: dict, tenant_id: str) -> int:
    """Replace the tenant's flags with this run's, all or nothing. `conn` is any DB-API connection.
    Returns the number of flags written; an empty run clears the tenant's old flags."""
    rows = build_rows(payload, tenant_id)
    try:
        with conn.cursor() as cur:
            cur.execute(DELETE_SQL, (tenant_id,))
            for row in rows:
                cur.execute(INSERT_SQL, row)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return len(rows)


def _uuid(value: str) -> str:
    return str(uuid.UUID(str(value)))


def _connect(dsn: str):
    try:
        import psycopg
    except ImportError as exc:  # the analysis itself needs no database driver
        raise SystemExit("publishing needs the driver: pip install -e \".[publish]\"") from exc
    return psycopg.connect(dsn)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m flow_health.publish",
                                description="Write one run's flags for one tenant into Chain's flow_health_flag table.")
    p.add_argument("--flags", type=Path, required=True, help="the detectors' flags.json")
    p.add_argument("--tenant", default=None, help="tenant uuid (default: meta.tenant_id of the file)")
    p.add_argument("--dsn", default=None, help=f"Postgres URL of Chain's database (default: ${DSN_ENV})")
    p.add_argument("--dry-run", action="store_true", help="check the file and print what would be written, write nothing")
    args = p.parse_args(argv)

    payload = json.loads(args.flags.read_text(encoding="utf-8"))
    try:
        tenant = resolve_tenant(payload, args.tenant)
        rows = build_rows(payload, tenant)
    except ValueError as exc:
        print(f"refusing to publish: {exc}", file=sys.stderr)
        return 2
    if args.dry_run:
        print(f"dry run: would replace tenant {tenant}'s flags with {len(rows)} from {args.flags}")
        return 0

    dsn = args.dsn or os.environ.get(DSN_ENV)
    if not dsn:
        print(f"refusing to publish: no database - pass --dsn or set ${DSN_ENV}", file=sys.stderr)
        return 2
    with _connect(dsn) as conn:
        written = publish(conn, payload, tenant)
    print(f"published {written} flags for tenant {tenant}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
