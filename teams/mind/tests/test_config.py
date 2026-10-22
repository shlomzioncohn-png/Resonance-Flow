"""
Tests for mind.config's settings from Relay's mounted files (#481).

vector_store.url/.user/.db come from $CONFIG_FILE_PATH and .password from
$SECRETS_FILE_PATH, and nothing else: a MIND_VECTOR_STORE__DSN env var is
ignored, and a missing piece stops Settings() with a ConfigFileError naming
it, instead of falling back to a default. service.log_level and
asr.whisper_model come from config.yaml too, their env var only filling in
when the key is absent.
"""

import pytest
import yaml
from pydantic import ValidationError

from mind.config import ASRSettings, Settings
from mind.config_file import ConfigFileError

_DEFAULT_VECTOR_STORE = {"url": "db.example:5432", "user": "mind", "db": "mind"}


@pytest.fixture
def chart_files(tmp_path, monkeypatch):
    """Write config.yaml/secrets.yaml and point the env at them; password=None writes no secrets.yaml."""

    def write(*, vector_store=_DEFAULT_VECTOR_STORE, password="secret", **sections):
        config_path = tmp_path / "config.yaml"
        secrets_path = tmp_path / "secrets.yaml"
        config_path.write_text(yaml.safe_dump({"vector_store": vector_store, **sections}))
        if password is not None:
            secrets_path.write_text(yaml.safe_dump({"vector_store": {"password": password}}))
        monkeypatch.setenv("CONFIG_FILE_PATH", str(config_path))
        monkeypatch.setenv("SECRETS_FILE_PATH", str(secrets_path))

    for var in ("MIND_VECTOR_STORE__DSN", "MIND_LOG_LEVEL", "WHISPER_MODEL"):
        monkeypatch.delenv(var, raising=False)
    return write


def _dsn(settings: Settings) -> str:
    return str(settings.vector_store.dsn.get_secret_value())


def test_builds_dsn_from_the_chart_files(chart_files):
    chart_files()

    settings = Settings(_env_file=None)  # type: ignore[call-arg]

    assert _dsn(settings) == "postgresql://mind:secret@db.example:5432/mind"


def test_env_var_dsn_is_ignored(chart_files, monkeypatch):
    """The file is the single source of truth: an env DSN can't redirect Mind."""

    chart_files(password="from-chart")
    monkeypatch.setenv("MIND_VECTOR_STORE__DSN", "postgresql://other:other@localhost:5432/other")

    settings = Settings(_env_file=None)  # type: ignore[call-arg]

    assert _dsn(settings) == "postgresql://mind:from-chart@db.example:5432/mind"


def test_env_can_still_set_the_non_connection_knobs(chart_files, monkeypatch):
    chart_files()
    monkeypatch.setenv("MIND_VECTOR_STORE__SEARCH_EFFORT", "50")

    settings = Settings(_env_file=None)  # type: ignore[call-arg]

    assert settings.vector_store.search_effort == 50
    assert _dsn(settings) == "postgresql://mind:secret@db.example:5432/mind"


def test_password_with_url_special_characters_is_quoted(chart_files):
    chart_files(password="p@ss/w:rd")

    settings = Settings(_env_file=None)  # type: ignore[call-arg]

    assert _dsn(settings) == "postgresql://mind:p%40ss%2Fw%3Ard@db.example:5432/mind"


def test_missing_secrets_file_fails_naming_the_password(chart_files):
    chart_files(password=None)

    with pytest.raises(
        ConfigFileError, match=r"vector_store\.password is missing from secrets\.yaml"
    ):
        Settings(_env_file=None)  # type: ignore[call-arg]


@pytest.mark.parametrize("password", ["", "   "])
def test_blank_password_fails(chart_files, password):
    chart_files(password=password)

    with pytest.raises(ConfigFileError, match=r"vector_store\.password is missing"):
        Settings(_env_file=None)  # type: ignore[call-arg]


def test_password_in_config_yaml_is_not_used(chart_files):
    """The ConfigMap is readable by anyone in the namespace; a password there doesn't count."""

    chart_files(
        vector_store={**_DEFAULT_VECTOR_STORE, "password": "in-the-configmap"}, password=None
    )

    with pytest.raises(
        ConfigFileError, match=r"vector_store\.password is missing from secrets\.yaml"
    ):
        Settings(_env_file=None)  # type: ignore[call-arg]


@pytest.mark.parametrize("key", ["url", "user", "db"])
def test_missing_connection_key_fails_naming_it(chart_files, key):
    chart_files(vector_store={k: v for k, v in _DEFAULT_VECTOR_STORE.items() if k != key})

    with pytest.raises(ConfigFileError, match=rf"vector_store\.{key} is missing from config\.yaml"):
        Settings(_env_file=None)  # type: ignore[call-arg]


def test_missing_vector_store_section_fails(chart_files):
    chart_files(vector_store=None)

    with pytest.raises(ConfigFileError, match=r"vector_store\.url is missing"):
        Settings(_env_file=None)  # type: ignore[call-arg]


def test_unset_config_file_path_fails_even_with_an_env_dsn(monkeypatch):
    monkeypatch.delenv("CONFIG_FILE_PATH", raising=False)
    monkeypatch.setenv("MIND_VECTOR_STORE__DSN", "postgresql://mind:mind@localhost:5432/mind")

    with pytest.raises(ConfigFileError, match=r"\$CONFIG_FILE_PATH is not set"):
        Settings(_env_file=None)  # type: ignore[call-arg]


def test_environment_defaults_to_local_when_unset(chart_files, monkeypatch):
    chart_files()
    monkeypatch.delenv("MIND_ENVIRONMENT", raising=False)

    settings = Settings(_env_file=None)  # type: ignore[call-arg]

    assert settings.environment == "local"


def test_the_object_storage_secret_never_prints():
    from mind.config import ObjectStorageSettings

    settings = ObjectStorageSettings(access_key="minio-user", secret_key="s3cr3t-value")

    assert "s3cr3t-value" not in repr(settings)
    assert settings.secret_key.get_secret_value() == "s3cr3t-value"


# --- ObjectStorageSettings: env first, then the cluster's mounted files ------


def _no_object_storage_env(monkeypatch):
    for name in ("ENDPOINT", "BUCKET", "ACCESS_KEY", "SECRET_KEY", "MAX_OBJECT_BYTES"):
        monkeypatch.delenv(f"MIND_OBJECT_STORAGE__{name}", raising=False)


def _cluster_files(tmp_path, monkeypatch):
    """What Relay's chart mounts: config.yaml, and one file per credential."""
    config = tmp_path / "config.yaml"
    config.write_text(
        "object_storage:\n  endpoint: http://minio.cluster:9000\n  bucket: cluster-bucket\n"
    )
    secrets_dir = tmp_path / "object-storage"
    secrets_dir.mkdir()
    (secrets_dir / "object-storage.access-key").write_text("cluster-user\n")
    (secrets_dir / "object-storage.secret-key").write_text("cluster-secret\n")
    monkeypatch.setenv("CONFIG_FILE_PATH", str(config))
    monkeypatch.setenv("OBJECT_STORAGE_SECRETS_DIR", str(secrets_dir))


def test_in_the_cluster_object_storage_comes_from_the_mounted_files(tmp_path, monkeypatch):
    from mind.config import ObjectStorageSettings

    _no_object_storage_env(monkeypatch)
    _cluster_files(tmp_path, monkeypatch)

    settings = ObjectStorageSettings(_env_file=None)

    assert (settings.endpoint, settings.bucket) == ("http://minio.cluster:9000", "cluster-bucket")
    assert settings.access_key == "cluster-user"
    assert settings.secret_key.get_secret_value() == "cluster-secret"


def test_an_environment_variable_wins_over_the_mounted_files(tmp_path, monkeypatch):
    from mind.config import ObjectStorageSettings

    _no_object_storage_env(monkeypatch)
    _cluster_files(tmp_path, monkeypatch)
    monkeypatch.setenv("MIND_OBJECT_STORAGE__BUCKET", "debug-bucket")

    assert ObjectStorageSettings(_env_file=None).bucket == "debug-bucket"


def test_without_mounted_files_the_local_defaults_hold(tmp_path, monkeypatch):
    from mind.config import ObjectStorageSettings

    _no_object_storage_env(monkeypatch)
    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "absent.yaml"))
    monkeypatch.setenv("OBJECT_STORAGE_SECRETS_DIR", str(tmp_path / "absent"))
    monkeypatch.setenv("MIND_OBJECT_STORAGE__ACCESS_KEY", "minioadmin")
    monkeypatch.setenv("MIND_OBJECT_STORAGE__SECRET_KEY", "minioadmin")

    settings = ObjectStorageSettings(_env_file=None)

    assert (settings.endpoint, settings.bucket) == ("http://localhost:9000", "bridge-raw")


# --- service.log_level ------------------------------------------------------


def test_log_level_comes_from_the_config_file(chart_files, monkeypatch):
    chart_files(service={"log_level": "DEBUG"})
    monkeypatch.setenv("MIND_LOG_LEVEL", "ERROR")

    assert Settings(_env_file=None).log_level == "DEBUG"  # type: ignore[call-arg]


def test_log_level_falls_back_to_env_then_info(chart_files, monkeypatch):
    chart_files()
    assert Settings(_env_file=None).log_level == "INFO"  # type: ignore[call-arg]

    monkeypatch.setenv("MIND_LOG_LEVEL", "WARNING")
    assert Settings(_env_file=None).log_level == "WARNING"  # type: ignore[call-arg]


def test_invalid_log_level_in_the_config_file_fails(chart_files):
    chart_files(service={"log_level": "VERBOSE"})

    with pytest.raises(ValidationError, match="log_level"):
        Settings(_env_file=None)  # type: ignore[call-arg]


# --- asr.whisper_model ------------------------------------------------------


def test_whisper_model_comes_from_the_config_file(chart_files, monkeypatch):
    chart_files(asr={"provider": "local", "whisper_model": "tiny.en"})
    monkeypatch.setenv("WHISPER_MODEL", "small.en")

    assert ASRSettings(_env_file=None).whisper_model == "tiny.en"  # type: ignore[call-arg]


def test_whisper_model_falls_back_to_env_then_the_135_decision(chart_files, monkeypatch):
    chart_files(asr={"whisper_model": ""})
    assert ASRSettings(_env_file=None).whisper_model == "base.en"  # type: ignore[call-arg]

    monkeypatch.setenv("WHISPER_MODEL", "small.en")
    assert ASRSettings(_env_file=None).whisper_model == "small.en"  # type: ignore[call-arg]


def test_asr_settings_need_the_config_file_too(monkeypatch):
    monkeypatch.delenv("CONFIG_FILE_PATH", raising=False)

    with pytest.raises(ConfigFileError, match=r"\$CONFIG_FILE_PATH is not set"):
        ASRSettings(_env_file=None)  # type: ignore[call-arg]
