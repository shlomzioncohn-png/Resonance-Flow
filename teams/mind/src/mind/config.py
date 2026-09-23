"""Application-wide settings, read from env vars (or a `.env` file), all
under the `MIND_` prefix. Nested settings use `__` as the delimiter --
`MIND_VECTOR_STORE__DSN` sets `Settings.vector_store.dsn`.

`environment` and `vector_store` have no defaults: constructing `Settings`
requires a real DSN and a real deployment target. Nothing in this module
calls `get_settings()` at import time, so importing `mind.config` itself
never requires either -- only actually calling `get_settings()` does (see
`api/main.py`'s lifespan and the test fixtures that call it explicitly).
"""

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, PostgresDsn, Secret
from pydantic_settings import BaseSettings, SettingsConfigDict

# Absolute, not ".env" -- a relative path is resolved against the current
# working directory, which silently finds nothing if pytest (or anything
# else) is ever run from somewhere other than teams/mind/.
_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"


class VectorStoreSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dsn: Secret[PostgresDsn]
    table: str = "task_embeddings"
    search_effort: int = Field(default=400, ge=1)
    embedding_dim: int = Field(default=256, ge=1)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="MIND_",
        env_nested_delimiter="__",
        env_file=_ENV_FILE,
        # "ignore", not "forbid": pydantic-settings' env_prefix only decides
        # which vars *map onto* a field -- it doesn't filter which vars from
        # `.env` get considered at all. That file also carries vars this
        # model was never meant to own (POSTGRES_USER, OCR_PROVIDER, ...,
        # consumed directly by docker-compose.yml or by other modules), so
        # "forbid" here rejected the whole file. VectorStoreSettings below
        # keeps "forbid": it only ever sees the already-prefix-stripped
        # MIND_VECTOR_STORE__* namespace, which it does fully own.
        extra="ignore",
    )

    environment: Literal["local", "ci", "staging", "production"]
    api_port: int = Field(default=8000, ge=1, le=65535)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = "INFO"
    duplicate_similarity_threshold: float = Field(default=0.90, ge=0.0, le=1.0)
    vector_store: VectorStoreSettings


@lru_cache
def get_settings() -> Settings:
    """Construct (once, cached) and return the Settings."""
    # environment/vector_store have no defaults on purpose (see the module
    # docstring) -- mypy can't see that pydantic-settings populates them
    # from the environment, so it flags this call as missing arguments.
    return Settings()  # type: ignore[call-arg]
