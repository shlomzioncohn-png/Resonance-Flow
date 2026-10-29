"""Alembic environment. The DSN comes from `mind.config.Settings` (env
vars / `.env`), never from `alembic.ini`'s `sqlalchemy.url` -- so no
connection string is ever committed to git, and this always talks to the
same database the app itself would connect to.

Synchronous on purpose, even though the app's own pool is async
(`psycopg_pool.AsyncConnectionPool`): Alembic's migration runner is
synchronous, and a migration is a one-off admin operation, not a
request-serving hot path -- there's no event loop here to protect. A
plain `create_engine()` with psycopg3's sync mode is simpler than
wiring `async_engine_from_config` for no real benefit.
"""

from logging.config import fileConfig

from alembic import context
from sqlalchemy import create_engine

from mind.config import get_settings

config = context.config
# `attributes={"configure_logger": False}` opts out (the test suite's
# `migrated_db` fixture does): alembic.ini's [loggers] section only lists
# root/sqlalchemy/alembic, so fileConfig's default disable_existing_loggers
# disables every other logger already created in the process -- every
# mind.* one, permanently, for whoever's running this.
if config.config_file_name is not None and config.attributes.get("configure_logger", True):
    fileConfig(config.config_file_name)

# Migrations in this project are hand-written (see versions/0001_*.py's
# module docstring for why -- pgvector's VECTOR(n) confuses autogenerate),
# so there's no ORM metadata to diff against.
target_metadata = None


def _dsn_for_sqlalchemy() -> str:
    # mind.config.VectorStoreSettings.dsn is a plain "postgresql://..."
    # URI (what psycopg3's own AsyncConnectionPool wants); SQLAlchemy
    # needs the dialect spelled out ("postgresql+psycopg://...") to know
    # to use psycopg3 instead of defaulting to psycopg2, which isn't
    # installed here.
    dsn = str(get_settings().vector_store.dsn.get_secret_value())
    if dsn.startswith("postgresql://"):
        dsn = "postgresql+psycopg://" + dsn.removeprefix("postgresql://")
    return dsn


def run_migrations_offline() -> None:
    context.configure(
        url=_dsn_for_sqlalchemy(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = create_engine(_dsn_for_sqlalchemy())
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
