"""Application-wide settings.

Connection settings come only from the files Relay's Helm chart mounts
(`mind.config_file`): `vector_store.dsn` is built from config.yaml's
`vector_store.url`/`.user`/`.db` and secrets.yaml's `vector_store.password`,
and an env var cannot override it (#481). The other keys the chart renders
and Mind uses -- `service.log_level`, `asr.whisper_model` -- also come from
config.yaml, with their env var only as a fallback when the key is absent.
Everything else -- tuning knobs such as the thresholds, `MIND_ENVIRONMENT` --
is read from env vars/`.env`, under the `MIND_` prefix (`__` for nesting).
"""

import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal, Self
from urllib.parse import quote
from uuid import UUID

from pydantic import (
    AnyHttpUrl,
    BaseModel,
    ConfigDict,
    Field,
    PostgresDsn,
    Secret,
    SecretStr,
    field_validator,
    model_validator,
)
from pydantic.fields import FieldInfo
from pydantic_settings import BaseSettings, PydanticBaseSettingsSource, SettingsConfigDict

from mind import config_file

# Absolute -- relative resolves against cwd, which breaks outside teams/mind/.
_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"

# The chart mounts the MinIO credentials as one file each (dependencySecretVolumes).
_DEFAULT_OBJECT_STORAGE_SECRETS_DIR = "/etc/resonance-flow/secrets/object-storage"


class _ConfigFileKeys(PydanticBaseSettingsSource):
    """Plain values from config.yaml, by `section.key` -- for chart keys whose
    setting still has a sensible default when the key is absent.

    Listed before env/.env in a settings class's sources, so the chart's value
    wins and the env var only fills in when config.yaml doesn't set the key.
    config.yaml itself is required: a missing or broken file still raises.
    """

    def __init__(self, settings_cls: type[BaseSettings], keys: dict[str, str]) -> None:
        super().__init__(settings_cls)
        # "asr.whisper_model" -> the name the settings class takes it under
        # (its validation_alias, if it has one).
        self._keys = keys

    def get_field_value(self, field: FieldInfo, field_name: str) -> tuple[Any, str, bool]:
        raise NotImplementedError  # unused: __call__ is overridden directly

    def __call__(self) -> dict[str, Any]:
        config = config_file.load_config()
        values: dict[str, Any] = {}
        for dotted, name in self._keys.items():
            section_name, key = dotted.split(".")
            value = config_file.section(config, section_name).get(key)
            if value is not None and str(value).strip():
                values[name] = value
        return values


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
    # config.yaml's asr.whisper_model (the chart sets it) wins over WHISPER_MODEL.
    whisper_model: str = Field(default="base.en", validation_alias="WHISPER_MODEL")
    whisper_models_dir: str | None = Field(default=None, validation_alias="WHISPER_MODELS_DIR")
    model_cache_dir: str = Field(default="./models", validation_alias="MODEL_CACHE_DIR")

    @property
    def download_dir(self) -> Path:
        return Path(self.model_cache_dir) / "whisper" / self.whisper_model

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        return (
            init_settings,
            _ConfigFileKeys(settings_cls, {"asr.whisper_model": "WHISPER_MODEL"}),
            env_settings,
            dotenv_settings,
            file_secret_settings,
        )


class _ChartObjectStorageSource(PydanticBaseSettingsSource):
    """MinIO from Relay's chart-mounted files: endpoint and bucket from
    config.yaml's `object_storage`, each credential from its own mounted file.
    Missing files give nothing, so a local run falls through to the defaults."""

    def get_field_value(self, field: FieldInfo, field_name: str) -> tuple[Any, str, bool]:
        raise NotImplementedError  # unused: __call__ is overridden directly

    def __call__(self) -> dict[str, Any]:
        # Lenient on purpose, for now: #349's local-defaults behaviour is kept
        # as it landed until aligning it with #481 is decided.
        object_storage = config_file.section(config_file.load_config_lenient(), "object_storage")
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


class _InboxConfigFileKeys(PydanticBaseSettingsSource):
    """The two connection settings of the Inbox directory, from the chart's files.

        config.yaml    inbox.url        -> base_url
        secrets.yaml   inbox.actor_id   -> actor_id

    The actor id is a secret, not a setting: Inbox trusts `X-Actor-Id` without
    checking it, so whoever knows this id acts as a SYSTEM_ADMIN.

    Read leniently, unlike the vector store: Inbox lookups are optional. A missing
    file or key means "not configured" and the client returns no candidates, where
    a missing vector store has to stop the service.
    """

    def get_field_value(self, field: FieldInfo, field_name: str) -> tuple[Any, str, bool]:
        raise NotImplementedError  # unused: __call__ is overridden directly

    def __call__(self) -> dict[str, Any]:
        inbox = config_file.section(config_file.load_merged_lenient(), "inbox")
        keys = {"url": "base_url", "actor_id": "actor_id"}
        return {name: inbox[key] for key, name in keys.items() if inbox.get(key) is not None}


class InboxDirectorySettings(BaseSettings):
    """Standalone, like EmbeddingModelSettings -- looking up a name needs no DSN.

    How the Inbox directory client (integrations/inbox_directory.py) reaches
    Inbox's Users/Teams endpoints. `base_url` and `actor_id` are connection
    settings, so in the cluster they come from the files Relay's chart mounts
    (see _InboxConfigFileKeys), and the env var is only a fallback for a local
    run. They have no default on purpose: the actor is a service user Inbox
    creates for Mind (#131), and a guessed id would make every call look like a
    permissions problem. Left unset, the client logs it and returns no
    candidates. The rest are tuning knobs and come from env, as elsewhere.
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

    @model_validator(mode="after")
    def _min_score_stays_below_a_partial_match(self) -> Self:
        # Imported here so that config does not import the matching at load time.
        from mind.integrations.name_matching import PARTIAL_SCORE

        if self.min_score >= PARTIAL_SCORE:
            raise ValueError(
                f"min_score must be below {PARTIAL_SCORE}, what a lone first name scores: "
                f"at {self.min_score} every partial match would be dropped, and #132 "
                "would never see two people with the same first name"
            )
        return self

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        # Earlier sources win: the chart's files before the env, which only
        # fills in when the files do not set the key.
        return (
            init_settings,
            _InboxConfigFileKeys(settings_cls),
            env_settings,
            dotenv_settings,
            file_secret_settings,
        )


class VectorStoreSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dsn: Secret[PostgresDsn]
    table: str = "task_embeddings"
    search_effort: int = Field(default=400, ge=1)
    # No embedding_dim: the column width is baked into the migration
    # (migrations/versions/0001_initial_vector_schema.py), not config.


def _required(section: dict[str, Any], key: str, where: str) -> str:
    value = section.get(key)
    if value is None or not str(value).strip():
        raise config_file.ConfigFileError(f"vector_store.{key} is missing from {where}")
    return str(value).strip()


class _ChartVectorStoreSource(PydanticBaseSettingsSource):
    """vector_store.dsn from Relay's chart-mounted files -- its only source."""

    def get_field_value(self, field: FieldInfo, field_name: str) -> tuple[Any, str, bool]:
        raise NotImplementedError  # unused: __call__ is overridden directly

    def __call__(self) -> dict[str, Any]:
        # Not load_merged(): the password is only taken from secrets.yaml,
        # never from the ConfigMap anyone in the namespace can read.
        vector_store = config_file.section(config_file.load_config(), "vector_store")
        vector_store_secret = config_file.section(config_file.load_secrets(), "vector_store")

        in_config = f"config.yaml (${config_file.CONFIG_FILE_PATH_ENV})"
        in_secrets = (
            f"secrets.yaml (${config_file.SECRETS_FILE_PATH_ENV}); in the cluster it comes "
            "from the resonance-flow-mind-secret Secret"
        )
        url = _required(vector_store, "url", in_config)
        user = _required(vector_store, "user", in_config)
        db = _required(vector_store, "db", in_config)
        password = _required(vector_store_secret, "password", in_secrets)

        # Quoted: a generated password may hold characters a URL gives meaning to.
        dsn = f"postgresql://{quote(user, safe='')}:{quote(password, safe='')}@{url}/{db}"
        return {"vector_store": {"dsn": dsn}}


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
    # config.yaml's service.log_level (the chart sets it) wins over MIND_LOG_LEVEL.
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
        # Earlier sources win. The chart source comes before env/.env, so a
        # MIND_VECTOR_STORE__DSN there is never used (#481); env can still set
        # the non-connection vector_store knobs (table, search_effort).
        return (
            init_settings,
            _ChartVectorStoreSource(settings_cls),
            _ConfigFileKeys(settings_cls, {"service.log_level": "log_level"}),
            env_settings,
            dotenv_settings,
            file_secret_settings,
        )


@lru_cache
def get_asr_settings() -> ASRSettings:
    """Construct (once, cached) and return the ASR settings.

    transcribe() needs them on every recording, so config.yaml is read once per
    process, not per call (#481 review). Raises ConfigFileError like
    get_settings(); a failure isn't cached, so a fixed config is picked up.
    """
    return ASRSettings()


@lru_cache
def get_settings() -> Settings:
    """Construct (once, cached) and return the Settings.

    Raises `mind.config_file.ConfigFileError` when the mounted config is
    missing or incomplete -- callers let it propagate, so the process stops.
    """
    # vector_store has no default -- mypy can't see pydantic-settings fills it in.
    return Settings()  # type: ignore[call-arg]
