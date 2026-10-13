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


class ASRSettings(BaseSettings):
    """Standalone, like EmbeddingModelSettings -- transcribe() needs no DSN.

    Where the weights are looked for, in order (see asr._model_path()):
    `<whisper_models_dir>/<whisper_model>/` if set -- the Dockerfile sets it to
    the copy baked into the image -- then `download_dir`, which is also where a
    local-dev fallback download lands (models/whisper/, see models/README.md).
    """

    model_config = SettingsConfigDict(env_file=_ENV_FILE, extra="ignore")

    # The #135 decision; must match CHOSEN_WHISPER_SIZE in benchmarks/asr_benchmark.py.
    whisper_model: str = Field(default="base.en", validation_alias="WHISPER_MODEL")
    whisper_models_dir: str | None = Field(default=None, validation_alias="WHISPER_MODELS_DIR")
    model_cache_dir: str = Field(default="./models", validation_alias="MODEL_CACHE_DIR")

    @property
    def download_dir(self) -> Path:
        return Path(self.model_cache_dir) / "whisper" / self.whisper_model


class ComputeCostSettings(BaseSettings):
    """Standalone, like ASRSettings -- recording a model call's cost needs no DSN.

    The price of one CPU-hour, which turns a local model call's measured CPU
    seconds into an estimated USD cost (see observability/compute.py). Unset by
    default: with no rate on file, a local call's cost is recorded as None
    (unpriced) rather than a made-up number.
    """

    model_config = SettingsConfigDict(
        env_prefix="MIND_COMPUTE_COST__", env_file=_ENV_FILE, extra="ignore"
    )

    usd_per_cpu_hour: float | None = Field(default=None, ge=0)


class MonitoringSettings(BaseSettings):
    """Standalone -- recording a model call needs no DSN.

    When a model call counts as "far slower / more expensive than expected" in
    the model-call summary (observability/tracking.py), and how often that
    summary is logged. None turns a check off. The defaults are starting
    points from the numbers measured so far, not validated targets:

    * OCR 30 s: ~2 s a page (multimodal/ocr.py, MAX_PAGES), so ~20 s for the
      longest document OCR reads by default.
    * ASR 120 s: base.en runs at ~10x real time, so ~60 s for the longest
      recording ASR decodes (MAX_DURATION_S = 600 s).
    * LLM 30 s: half the provider deadline (60 s, extraction/providers).
    * LLM $0.01: several times a DeepSeek extraction of a few thousand tokens.
      OCR/ASR have no cost check by default: their cost needs a CPU rate first.
    """

    # "none" in the env turns a check off (an empty value would not parse).
    model_config = SettingsConfigDict(
        env_prefix="MIND_MONITORING__",
        env_file=_ENV_FILE,
        extra="ignore",
        env_parse_none_str="none",
    )

    ocr_max_duration_ms: float | None = Field(default=30_000, gt=0)
    asr_max_duration_ms: float | None = Field(default=120_000, gt=0)
    llm_max_duration_ms: float | None = Field(default=30_000, gt=0)
    ocr_max_cost_usd: float | None = Field(default=None, ge=0)
    asr_max_cost_usd: float | None = Field(default=None, ge=0)
    llm_max_cost_usd: float | None = Field(default=0.01, ge=0)
    # Log the summary every this many model calls; 0 turns it off.
    summary_every_n_calls: int = Field(default=100, ge=0)


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
    duplicate_similarity_threshold: float = Field(default=0.90, ge=0.0, le=1.0)
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
    # vector_store has no default (see VectorStoreSettings) -- mypy can't
    # see that pydantic-settings fills it in from the env/chart sources above.
    return Settings()  # type: ignore[call-arg]
