"""
Tests for mind.config's chart-config fallback (relay#512): when the pod is
deployed via Relay's Helm chart, there's no MIND_VECTOR_STORE__DSN env var --
vector_store.url/.user/.db arrive via $CONFIG_FILE_PATH and .password via
$SECRETS_FILE_PATH. Settings() should build a DSN from those as a fallback,
with any real MIND_VECTOR_STORE__DSN (env var or .env) always winning.
"""

import pytest
import yaml
from pydantic import ValidationError

from mind.config import Settings


def _write_chart_files(tmp_path, *, url="db.example:5432", user="mind", db="mind", password="secret"):
    config_path = tmp_path / "config.yaml"
    secrets_path = tmp_path / "secrets.yaml"
    config_path.write_text(yaml.dump({"vector_store": {"url": url, "user": user, "db": db}}))
    secrets_path.write_text(yaml.dump({"vector_store": {"password": password}}))
    return config_path, secrets_path


def test_builds_dsn_from_chart_config_when_no_env_var_is_set(tmp_path, monkeypatch):
    config_path, secrets_path = _write_chart_files(tmp_path)
    monkeypatch.delenv("MIND_VECTOR_STORE__DSN", raising=False)
    monkeypatch.setenv("CONFIG_FILE_PATH", str(config_path))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(secrets_path))

    settings = Settings(_env_file=None)  # type: ignore[call-arg]

    assert str(settings.vector_store.dsn.get_secret_value()) == "postgresql://mind:secret@db.example:5432/mind"


def test_env_var_dsn_wins_over_chart_config(tmp_path, monkeypatch):
    config_path, secrets_path = _write_chart_files(tmp_path, password="from-chart")
    monkeypatch.setenv("CONFIG_FILE_PATH", str(config_path))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(secrets_path))
    monkeypatch.setenv("MIND_VECTOR_STORE__DSN", "postgresql://mind:mind@localhost:5432/mind")

    settings = Settings(_env_file=None)  # type: ignore[call-arg]

    assert str(settings.vector_store.dsn.get_secret_value()) == "postgresql://mind:mind@localhost:5432/mind"


def test_missing_chart_password_contributes_nothing(tmp_path, monkeypatch):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(yaml.dump({"vector_store": {"url": "db.example:5432"}}))
    monkeypatch.delenv("MIND_VECTOR_STORE__DSN", raising=False)
    monkeypatch.setenv("CONFIG_FILE_PATH", str(config_path))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "does-not-exist.yaml"))

    with pytest.raises(ValidationError, match="vector_store"):
        Settings(_env_file=None)  # type: ignore[call-arg]


def test_environment_defaults_to_local_when_unset(tmp_path, monkeypatch):
    config_path, secrets_path = _write_chart_files(tmp_path)
    monkeypatch.delenv("MIND_ENVIRONMENT", raising=False)
    monkeypatch.setenv("CONFIG_FILE_PATH", str(config_path))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(secrets_path))

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
