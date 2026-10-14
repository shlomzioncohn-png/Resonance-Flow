"""Application-wide settings, read from env vars/`.env`, under the
`MIND_` prefix (`__` for nesting). In-cluster, `vector_store.dsn` falls
back to Relay's chart-mounted config/secret files instead.
"""

import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field, PostgresDsn, Secret
from pydantic.fields import FieldInfo
from pydantic_settings import BaseSettings, PydanticBaseSettingsSource, SettingsConfigDict

# Absolute -- relative resolves against cwd, which breaks outside teams/mind/.
_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"

_DEFAULT_CONFIG_FILE = "/etc/resonance-flow/config/config.yaml"
_DEFAULT_SECRETS_FILE = "/etc/resonance-flow/secret/secrets.yaml"


def _read_yaml(path: str) -> dict[str, Any]:
    p = Path(path)
    return yaml.safe_load(p.read_text()) if p.is_file() else {}


class EmbeddingModelSettings(BaseSettings):
    """Standalone, not nested under Settings -- embed() needs no DSN."""

    model_config = SettingsConfigDict(
        env_prefix="MIND_EMBEDDING_MODEL__", env_file=_ENV_FILE, extra="ignore"
    )

    name: str = "BAAI/bge-small-en-v1.5"
    model_cache_dir: str | None = Field(default=None, validation_alias="MODEL_CACHE_DIR")


class VectorStoreSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dsn: Secret[PostgresDsn]
    table: str = "task_embeddings"
    search_effort: int = Field(default=400, ge=1)
    # No embedding_dim: the column width is baked into the migration
    # (migrations/versions/0001_initial_vector_schema.py), not config.


class _ChartVectorStoreSource(PydanticBaseSettingsSource):
    """Fallback vector_store.dsn from Relay's chart-mounted files."""

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
        # "ignore": .env also carries vars this model doesn't own.
        extra="ignore",
    )

    environment: Literal["local", "ci", "staging", "production"] = "local"
    api_port: int = Field(default=8000, ge=1, le=65535)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = "INFO"
    # Calibrated via benchmarks/threshold_calibration.py, not a guess -- see
    # embeddings/README.md "Threshold calibration" for the table and method.
    duplicate_similarity_threshold: float = Field(default=0.761, ge=0.0, le=1.0)
    dependency_match_threshold: float = Field(default=0.666, ge=0.0, le=1.0)
    vector_store: VectorStoreSettings
    embedding_model: EmbeddingModelSettings = Field(default_factory=EmbeddingModelSettings)

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        # Earlier sources win; chart source only fills gaps (in-cluster).
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
    return Settings()  # type: ignore[call-arg]
