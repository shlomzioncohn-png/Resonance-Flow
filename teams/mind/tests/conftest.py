"""
Test-session setup, shared across every test module in this package.

Also generates the messaging module's proto classes before tests run, in case
pytest was invoked directly (not via `make test`), see `make proto`.
"""

import asyncio
import atexit
import os
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Iterator
from pathlib import Path
from urllib.parse import unquote, urlsplit

import pytest
import yaml
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


def _write_session_config_files() -> None:
    """Give the session the mounted files mind.config now requires (#481).

    Settings reads vector_store only from $CONFIG_FILE_PATH/$SECRETS_FILE_PATH,
    never from MIND_VECTOR_STORE__DSN -- so turn that DSN (from .env or CI's
    TEST_DATABASE_URL, above) into the two files, the same split Relay's chart
    uses. Without one, a placeholder that nothing connects to: DB tests read
    MIND_VECTOR_STORE__DSN themselves and skip/fail on its absence as before.
    """
    dsn = urlsplit(
        os.environ.get(
            "MIND_VECTOR_STORE__DSN", "postgresql://mind:placeholder@db.invalid:5432/mind"
        )
    )
    directory = Path(tempfile.mkdtemp(prefix="mind-test-config-"))
    atexit.register(shutil.rmtree, directory, ignore_errors=True)
    config = {
        "vector_store": {
            "url": f"{dsn.hostname}:{dsn.port or 5432}",
            "user": unquote(dsn.username or ""),
            "db": dsn.path.lstrip("/"),
        }
    }
    secrets = {"vector_store": {"password": unquote(dsn.password or "")}}
    (directory / "config.yaml").write_text(yaml.safe_dump(config), encoding="utf-8")
    (directory / "secrets.yaml").write_text(yaml.safe_dump(secrets), encoding="utf-8")
    os.environ["CONFIG_FILE_PATH"] = str(directory / "config.yaml")
    os.environ["SECRETS_FILE_PATH"] = str(directory / "secrets.yaml")


# A caller that already points at a config file (e.g. a local run against a
# real one) keeps it.
if "CONFIG_FILE_PATH" not in os.environ:
    _write_session_config_files()


@pytest.fixture(autouse=True)
def _default_monitoring_limits(monkeypatch: pytest.MonkeyPatch) -> None:
    """Pin the slow/expensive limits and summary interval to their defaults.

    MIND_MONITORING__* in a developer's .env (loaded above) must not change
    what a test expects. A test that needs other limits sets them itself.
    """
    from mind.config import MonitoringSettings
    from mind.observability import tracking

    monkeypatch.setattr(tracking, "monitoring_settings", MonitoringSettings.model_construct)


@pytest.fixture(autouse=True)
def _fresh_asr_settings() -> Iterator[None]:
    """get_asr_settings() is cached per process; tests set WHISPER_MODEL /
    CONFIG_FILE_PATH per test, so each one starts from an empty cache."""
    from mind.config import get_asr_settings

    get_asr_settings.cache_clear()
    yield
    get_asr_settings.cache_clear()


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
    # configure_logger=False: env.py's fileConfig(alembic.ini) would otherwise
    # disable every mind.* logger already created in this process (Python's
    # fileConfig default disable_existing_loggers=True, and alembic.ini's own
    # [loggers] section only lists root/sqlalchemy/alembic) -- breaking every
    # later test's caplog assertions for the rest of the session.
    config = Config(str(_ROOT / "alembic.ini"), attributes={"configure_logger": False})
    alembic_command.upgrade(config, "head")


# Always regenerate (not "if missing") -- a proto edit with the old pb2
# still on disk would otherwise make tests run against stale generated
# code. See scripts/gen_proto.py -- the one place that knows the actual
# protoc invocations, also used by `make proto` and the Dockerfile.
subprocess.run(["python", str(_ROOT / "scripts" / "gen_proto.py")], check=True)
