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
