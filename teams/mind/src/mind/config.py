"""Application-wide settings, read from env vars/`.env`, under the
`MIND_` prefix (`__` for nesting). In-cluster, `vector_store.dsn` falls
back to Relay's chart-mounted config/secret files instead.
"""

import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal
from uuid import UUID

import yaml
from pydantic import (
    AnyHttpUrl,
    BaseModel,
    ConfigDict,
    Field,
    PostgresDsn,
    Secret,
    SecretStr,
    field_validator,
)
from pydantic.fields import FieldInfo
from pydantic_settings import BaseSettings, PydanticBaseSettingsSource, SettingsConfigDict

# Absolute -- relative resolves against cwd, which breaks outside teams/mind/.
_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"

_DEFAULT_CONFIG_FILE = "/etc/resonance-flow/config/config.yaml"
_DEFAULT_SECRETS_FILE = "/etc/resonance-flow/secret/secrets.yaml"
# The chart mounts the MinIO credentials as one file each (dependencySecretVolumes).
_DEFAULT_OBJECT_STORAGE_SECRETS_DIR = "/etc/resonance-flow/secrets/object-storage"


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


class _ChartObjectStorageSource(PydanticBaseSettingsSource):
    """MinIO from Relay's chart-mounted files: endpoint and bucket from
    config.yaml's `object_storage`, each credential from its own mounted file.
    Missing files give nothing, so a local run falls through to the defaults."""

    def get_field_value(self, field: FieldInfo, field_name: str) -> tuple[Any, str, bool]:
        raise NotImplementedError  # unused: __call__ is overridden directly

    def __call__(self) -> dict[str, Any]:
        config = _read_yaml(os.environ.get("CONFIG_FILE_PATH", _DEFAULT_CONFIG_FILE))
        object_storage = (config or {}).get("object_storage") or {}
        values = {k: object_storage[k] for k in ("endpoint", "bucket") if object_storage.get(k)}

        secrets_dir = Path(
            os.environ.get("OBJECT_STORAGE_SECRETS_DIR", _DEFAULT_OBJECT_STORAGE_SECRETS_DIR)
        )
        for field_name, filename in (
            ("access_key", "object-storage.access-key"),
            ("secret_key", "object-storage.secret-key"),
        ):
            path = secrets_dir / filename
            if path.is_file():
                values[field_name] = path.read_text().strip()  # mounted files end in a newline
        return values


class ObjectStorageSettings(BaseSettings):
    """Standalone -- the bridge.raw.landed consumer (#349) reads Bridge's objects.

    Sources, first wins: environment variables, .env, then the cluster's
    mounted files (_ChartObjectStorageSource), then the defaults. The defaults
    match Bridge's own local ones (teams/bridge application.yml); the
    credentials have none, so a missing one fails at start-up.
    """

    model_config = SettingsConfigDict(
        env_prefix="MIND_OBJECT_STORAGE__", env_file=_ENV_FILE, extra="ignore"
    )

    endpoint: str = "http://localhost:9000"
    bucket: str = "bridge-raw"
    access_key: str
    secret_key: SecretStr  # never printed: repr, logs, tracebacks show '**********'
    # Larger objects are skipped, not read: the process's memory is the limit.
    max_object_bytes: int = Field(default=100 * 1024 * 1024, gt=0)

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        # Earlier sources win; the chart's files only fill what env leaves unset.
        return (
            init_settings,
            env_settings,
            dotenv_settings,
            _ChartObjectStorageSource(settings_cls),
            file_secret_settings,
        )


class LandedConsumerSettings(BaseSettings):
    """Standalone -- the bridge.raw.landed runner (#349) needs no database, so it
    does not read `Settings`, which requires the vector-store DSN.

    Read from the environment and .env like every other MIND_* setting, so an
    override set next to the MinIO keys in .env is not silently ignored.
    """

    model_config = SettingsConfigDict(
        env_prefix="MIND_LANDED_CONSUMER__", env_file=_ENV_FILE, extra="ignore"
    )

    # Its own group, not the one in ProtobufConsumer's docstring example. Until
    # extraction is wired, a run marks items processed and commits their
    # offsets with nothing extracted: a test run against the shared topic sets
    # MIND_LANDED_CONSUMER__GROUP_ID to a group of its own.
    group_id: str = "mind-landed-consumer"
    # The same variable `Settings.log_level` reads.
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = Field(
        default="INFO", validation_alias="MIND_LOG_LEVEL"
    )


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
    * LLM 10 s: R2 measured ~1.4 s a call on average and 2.2 s at the slowest
      with the paid key (the provider deadline is 60 s), so ~5x the slowest.
    * LLM $0.02: per R2, a typical item costs ~$0.0024 and the heaviest (full
      content, full output) ~$0.008 today. gemini-3.6-flash's price doubles
      on 1.1.2027 (heaviest then ~$0.016), so $0.02 stays above a legitimate
      call after that too -- while still ~8x a typical item today.

    Only LLM calls have a cost limit: they are the only calls priced in
    dollars. OCR/ASR cost CPU seconds, and a CPU-seconds limit waits for a
    measured baseline (#479).
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
    llm_max_duration_ms: float | None = Field(default=10_000, gt=0)
    llm_max_cost_usd: float | None = Field(default=0.02, ge=0)
    # Log the summary every this many model calls; 0 or none turns it off.
    summary_every_n_calls: int | None = Field(default=100, ge=0)


class InboxDirectorySettings(BaseSettings):
    """Standalone, like EmbeddingModelSettings -- looking up a name needs no DSN.

    How the Inbox directory client (integrations/inbox_directory.py) reaches
    Inbox's Users/Teams endpoints. `base_url` and `actor_id` have no default on
    purpose: the actor is a service user Inbox creates for Mind (#131), and a
    guessed id would make every call look like a permissions problem. Left
    unset, the client logs it and returns no candidates.
    """

    model_config = SettingsConfigDict(
        env_prefix="MIND_INBOX_DIRECTORY__", env_file=_ENV_FILE, extra="ignore"
    )

    # An http(s) URL, checked when the settings load: an address with a typo
    # (no "http://", a port that is not a number) stops Mind at start-up with
    # the setting named, instead of looking like Inbox being down on every call.
    # Empty means unset, see _blank_means_unset.
    base_url: AnyHttpUrl | None = None
    # Sent as X-Actor-Id. Its role decides what Inbox returns: TENANT_ADMIN or
    # SYSTEM_ADMIN see everyone, a TEAM_LEAD or EMPLOYEE gets a partial list.
    actor_id: UUID | None = None
    # Per HTTP call.
    timeout_seconds: float = Field(default=5.0, gt=0)
    # For reading both lists of a tenant together, however many pages it takes.
    max_fetch_seconds: float = Field(default=15.0, gt=0)
    # 0 turns the cache off.
    cache_ttl_seconds: float = Field(default=300.0, ge=0)
    # Candidates scoring below this are dropped. A name that fails the word
    # rule in integrations/name_matching.py scores 0 and a name that passes it
    # scores at least WORD_MATCH_MIN * PARTIAL_SCORE (about 0.5), so this is a
    # floor under the rule, not the rule: 0.4 drops nothing the rule lets through.
    # Raise it only to drop weak matches such as a nickname or a typo in a lone
    # first name; it must stay below PARTIAL_SCORE (0.7).
    min_score: float = Field(default=0.4, ge=0.0, le=1.0)

    @field_validator("base_url", "actor_id", mode="before")
    @classmethod
    def _blank_means_unset(cls, value: object) -> object:
        # A variable that is exported but empty (`MIND_...__ACTOR_ID=`) arrives
        # as "" and would otherwise be a bad UUID, or an address that counts as
        # configured. Both mean "not set".
        if isinstance(value, str):
            return value.strip() or None
        return value


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
    related_task_threshold: float = Field(default=0.711, ge=0.0, le=1.0)
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
    # vector_store has no default -- mypy can't see pydantic-settings fills it in.
    return Settings()  # type: ignore[call-arg]
