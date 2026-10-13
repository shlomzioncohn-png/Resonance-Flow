"""
Test-session setup, shared across every test module in this package.

Also generates the messaging module's proto classes before tests run, in case
pytest was invoked directly (not via `make test`), see `make proto`. The
`migrated_db` fixture below applies the Alembic migration -- see its
own docstring for why that's a fixture, not module-level code.
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


@pytest.fixture(autouse=True)
def _no_compute_cost_rate(monkeypatch: pytest.MonkeyPatch) -> None:
    """Pin MIND_COMPUTE_COST__USD_PER_CPU_HOUR to unset for every test.

    A rate in a developer's .env (loaded above) must not change what a test
    expects an OCR/ASR call to cost. A test that needs a rate sets one itself.
    """
    from mind.observability import compute

    monkeypatch.setattr(compute, "usd_per_cpu_hour", lambda: None)


@pytest.fixture(autouse=True)
def _default_monitoring_limits(monkeypatch: pytest.MonkeyPatch) -> None:
    """Pin the slow/expensive limits and summary interval to their defaults.

    Same reason as above: MIND_MONITORING__* in a developer's .env must not
    change what a test expects. A test that needs other limits sets them itself.
    """
    from mind.config import MonitoringSettings
    from mind.observability import tracking

    monkeypatch.setattr(tracking, "monitoring_settings", MonitoringSettings.model_construct)


@pytest.fixture(scope="session")
def migrated_db() -> None:
    """Applies `alembic upgrade head` once per test session. PgVectorStore
    no longer creates its own schema (issue #441) -- the real
    `task_embeddings` table only exists once this has run.

    A fixture, not module-level code: only tests that actually need a
    migrated DB (the `store` fixtures) should pull this in. Running it
    at import time instead would force every test file through it,
    including DB-free ones like test_config.py -- if the DB container
    happens to be down, those would fail too, for no reason of their own.

    Runs against whatever Postgres CI/local already pointed
    MIND_VECTOR_STORE__DSN at -- this is the real verification that the
    migration applies cleanly, not just a fixture setup step. Uses
    Alembic's own Python API, not a subprocess -- a real traceback on
    failure, and no dependency on `alembic` being on PATH.
    """
    # The migration deliberately never runs CREATE EXTENSION (needs
    # superuser; belongs in db/init/01-enable-pgvector.sql for a real
    # deploy, see migrations/versions/0001's docstring). Locally that
    # init script handles it. In CI, the shared Postgres setup-service-
    # infra port-forwards (relay#513) never runs it either -- nothing
    # else in that path does -- so the migration hits "type vector does
    # not exist" otherwise. CI's TEST_DATABASE_URL connects as the
    # `postgres` superuser specifically so tests can do this themselves.
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
