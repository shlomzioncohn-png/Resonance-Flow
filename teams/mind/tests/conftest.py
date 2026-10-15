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

import pytest
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


@pytest.fixture(scope="session")
def migrated_db() -> None:
    """Applies `alembic upgrade head` once per session. A fixture (not
    module-level code) so only DB tests (the `store` fixtures) pull it
    in -- DB-free files like test_config.py shouldn't need a live DB.
    Alembic's Python API, not a subprocess -- no dependency on `alembic`
    being on PATH."""
    # CREATE EXTENSION needs superuser; CI's TEST_DATABASE_URL is one.
    import psycopg
    from alembic import command as alembic_command
    from alembic.config import Config

    with psycopg.connect(os.environ["MIND_VECTOR_STORE__DSN"], autocommit=True) as conn:
        conn.execute("CREATE EXTENSION IF NOT EXISTS vector;")
    alembic_command.upgrade(Config(str(_ROOT / "alembic.ini")), "head")


# Always regenerate (not "if missing") -- a proto edit with the old pb2
# still on disk would otherwise make tests run against stale generated
# code. See scripts/gen_proto.py -- the one place that knows the actual
# protoc invocations, also used by `make proto` and the Dockerfile.
subprocess.run(["python", str(_ROOT / "scripts" / "gen_proto.py")], check=True)
