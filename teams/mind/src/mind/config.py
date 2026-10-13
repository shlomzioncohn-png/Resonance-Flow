"""Application-wide settings, read from env vars (or a `.env` file), all
under the `MIND_` prefix. Nested settings use `__` as the delimiter --
`MIND_VECTOR_STORE__DSN` sets `Settings.vector_store.dsn`.

In-cluster (Relay's Helm chart), there's no `MIND_VECTOR_STORE__DSN` env
var -- `vector_store.url`/`.user`/`.db` arrive via `$CONFIG_FILE_PATH`
(non-secret) and `vector_store.password` via `$SECRETS_FILE_PATH` (secret),
the same file-mount pattern `messaging/config.py` already reads (see
relay#512). `_ChartVectorStoreSource` below builds a DSN from those and
contributes it as a fallback -- env vars / `.env` (checked first, see
`settings_customise_sources`) still win for local runs and tests.

`vector_store` has no default: constructing `Settings` requires a real DSN
from *some* source. Nothing in this module calls `get_settings()` at
import time, so importing `mind.config` itself never requires one -- only
actually calling `get_settings()` does (see `api/main.py`'s lifespan and
the test fixtures that call it explicitly).
"""

import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field, PostgresDsn, Secret
from pydantic.fields import FieldInfo
from pydantic_settings import BaseSettings, PydanticBaseSettingsSource, SettingsConfigDict

# Absolute, not ".env" -- a relative path is resolved against the current
# working directory, which silently finds nothing if pytest (or anything
# else) is ever run from somewhere other than teams/mind/.
_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"

# Same defaults and env var names as messaging/config.py, for consistency --
# both read whatever Relay's chart mounts into the pod.
_DEFAULT_CONFIG_FILE = "/etc/resonance-flow/config/config.yaml"
_DEFAULT_SECRETS_FILE = "/etc/resonance-flow/secret/secrets.yaml"


def _read_yaml(path: str) -> dict[str, Any]:
    p = Path(path)
    return yaml.safe_load(p.read_text()) if p.is_file() else {}


class VectorStoreSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dsn: Secret[PostgresDsn]
    table: str = "task_embeddings"
    search_effort: int = Field(default=400, ge=1)
    embedding_dim: int = Field(default=256, ge=1)


class _ChartVectorStoreSource(PydanticBaseSettingsSource):
    """Builds `vector_store.dsn` from Relay's chart-mounted config/secret
    files, as a fallback source when nothing else (env vars, `.env`)
    already provided one -- see the module docstring."""

    def get_field_value(self, field: FieldInfo, field_name: str) -> tuple[Any, str, bool]:
        raise NotImplementedError  # unused: __call__ is overridden directly

    def __call__(self) -> dict[str, Any]:
        config = _read_yaml(os.environ.get("CONFIG_FILE_PATH", _DEFAULT_CONFIG_FILE))
        secrets = _read_yaml(os.environ.get("SECRETS_FILE_PATH", _DEFAULT_SECRETS_FILE))
        vector_store = (config or {}).get("vector_store") or {}
        vector_store_secret = (secrets or {}).get("vector_store") or {}

        url = vector_store.get("url")
        password = vector_store_secret.get("password")
        if not url or not password:
            return {}

        user = vector_store.get("user", "mind")
        db = vector_store.get("db", "mind")
        return {"vector_store": {"dsn": f"postgresql://{user}:{password}@{url}/{db}"}}


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

    # No read anywhere yet (kept for env-gated behavior later, e.g. log
    # verbosity) -- defaulted, not required, so its absence never blocks
    # startup. CI/local already export MIND_ENVIRONMENT explicitly.
    environment: Literal["local", "ci", "staging", "production"] = "local"
    api_port: int = Field(default=8000, ge=1, le=65535)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = "INFO"
    duplicate_similarity_threshold: float = Field(default=0.90, ge=0.0, le=1.0)
    vector_store: VectorStoreSettings

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        # Earlier sources win. env/.env (MIND_VECTOR_STORE__DSN) stay
        # authoritative for local runs and tests; the chart source only
        # fills in vector_store.dsn when nothing else did -- i.e. in-cluster.
        return (
            init_settings,
            env_settings,
            dotenv_settings,
            _ChartVectorStoreSource(settings_cls),
            file_secret_settings,
        )


@lru_cache
def get_settings() -> Settings:
    """Construct (once, cached) and return the Settings."""
    # vector_store has no default on purpose (see the module docstring) --
    # mypy can't see that pydantic-settings populates it from the
    # environment or the chart config, so it flags this call as missing.
    return Settings()  # type: ignore[call-arg]
