"""
Test-session setup, shared across every test module in this package.

Also generates the messaging module's proto classes before tests run, in case
pytest was invoked directly (not via `make test`), see `make proto`.
"""

import asyncio
import os
import subprocess
import sys
from pathlib import Path

from dotenv import load_dotenv

if sys.platform == "win32":
    # psycopg3's async mode rejects Windows' default ProactorEventLoop.
    # Must be set before pytest-asyncio creates its first loop. Linux (the
    # deployment target) never hits this.
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

_ROOT = Path(__file__).resolve().parent.parent

# tests/test_vector_store.py reads MIND_VECTOR_STORE__DSN straight from
# os.environ (deliberately, not via mind.config.Settings -- see its `store`
# fixture docstring). Locally there's no step that puts it there -- `.env`
# is just a file until something loads it -- so load it here. Never
# overrides a var already set, and silently no-ops if `.env` doesn't exist.
load_dotenv(_ROOT / ".env")

# In CI, setup-service-infra port-forwards the shared Postgres (pgvector
# overlaid on it, see relay#513 / issue #490) and exports TEST_DATABASE_URL,
# not MIND_VECTOR_STORE__DSN directly -- map it. setdefault so a real local
# `.env` value (loaded above) always wins.
if "TEST_DATABASE_URL" in os.environ:
    os.environ.setdefault("MIND_VECTOR_STORE__DSN", os.environ["TEST_DATABASE_URL"])
os.environ.setdefault("MIND_ENVIRONMENT", "ci")

# PgVectorStore no longer creates its own schema (issue #441) -- the real
# `task_embeddings` table only exists once `alembic upgrade head` has run.
# Only when a real DSN is actually configured, since plenty of test files
# (test_loader.py, test_config.py, ...) need no DB at all and shouldn't be
# forced through this. Runs against whatever Postgres CI/local already
# pointed MIND_VECTOR_STORE__DSN at -- this is the real verification that
# the migration applies cleanly, not just a fixture setup step.
if "MIND_VECTOR_STORE__DSN" in os.environ:
    # The migration deliberately never runs CREATE EXTENSION (needs
    # superuser; belongs in db/init/01-enable-pgvector.sql for a real
    # deploy, see migrations/versions/0001's docstring). Locally that
    # init script handles it. In CI, the shared Postgres setup-service-
    # infra port-forwards (relay#513) never runs it either -- nothing
    # else in that path does -- so the migration hits "type vector does
    # not exist" otherwise. CI's TEST_DATABASE_URL connects as the
    # `postgres` superuser specifically so tests can do this themselves.
    import psycopg

    with psycopg.connect(os.environ["MIND_VECTOR_STORE__DSN"], autocommit=True) as _conn:
        _conn.execute("CREATE EXTENSION IF NOT EXISTS vector;")
    subprocess.run(["alembic", "upgrade", "head"], cwd=_ROOT, check=True)

# Always regenerate (not "if missing") -- a proto edit with the old pb2
# still on disk would otherwise make tests run against stale generated
# code. See scripts/gen_proto.py -- the one place that knows the actual
# protoc invocations, also used by `make proto` and the Dockerfile.
subprocess.run(["python", str(_ROOT / "scripts" / "gen_proto.py")], check=True)
