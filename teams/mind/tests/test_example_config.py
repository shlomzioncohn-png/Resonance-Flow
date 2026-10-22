"""The committed example config files (#481) load, and stay in step with the
placeholders .env.example gives the local `vector-store` container -- a
mismatch there only shows up as an auth error at start-up.
"""

import re
from pathlib import Path

import pytest
import yaml

from mind.config import Settings

_ROOT = Path(__file__).resolve().parent.parent


def _env_example() -> dict[str, str]:
    lines = (_ROOT / ".env.example").read_text(encoding="utf-8").splitlines()
    return dict(line.split("=", 1) for line in lines if re.match(r"^[A-Z_]+=", line))


def _yaml(name: str) -> dict:
    return yaml.safe_load((_ROOT / name).read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    ("config_name", "expected_dsn"),
    [
        ("config.example.yml", "postgresql://mind:mind@localhost:5432/mind"),
        ("config.compose.yml", "postgresql://mind:mind@vector-store:5432/mind"),
    ],
)
def test_example_config_builds_settings(monkeypatch, config_name, expected_dsn):
    monkeypatch.setenv("CONFIG_FILE_PATH", str(_ROOT / config_name))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(_ROOT / "secrets.example.yml"))
    monkeypatch.delenv("MIND_LOG_LEVEL", raising=False)

    settings = Settings(_env_file=None)  # type: ignore[call-arg]

    assert str(settings.vector_store.dsn.get_secret_value()) == expected_dsn
    assert settings.log_level == "INFO"


@pytest.mark.parametrize("config_name", ["config.example.yml", "config.compose.yml"])
def test_example_config_matches_the_local_database(config_name):
    env = _env_example()
    vector_store = _yaml(config_name)["vector_store"]

    assert (vector_store["user"], vector_store["db"]) == (env["POSTGRES_USER"], env["POSTGRES_DB"])


def test_example_secrets_hold_only_the_local_placeholder_password():
    assert _yaml("secrets.example.yml") == {
        "vector_store": {"password": _env_example()["POSTGRES_PASSWORD"]}
    }


def _keys(tree: dict) -> set[str]:
    return {
        k
        for key, value in tree.items()
        for k in (key, *(_keys(value) if isinstance(value, dict) else ()))
    }


@pytest.mark.parametrize("config_name", ["config.example.yml", "config.compose.yml"])
def test_example_configs_carry_no_credentials(config_name):
    """Credentials belong in the secrets file, never the config (a ConfigMap in the cluster)."""

    keys = _keys(_yaml(config_name))

    assert not keys & {"password", "api_key", "access_key", "secret_key", "sasl"}


def test_make_local_targets_point_at_files_that_exist():
    makefile = (_ROOT / "Makefile").read_text(encoding="utf-8")
    lines = re.findall(r"^([\w -]+): export (\w+) = (\S+)$", makefile, re.MULTILINE)
    # demo-check-media too: ASR reads config.yaml (#481 review).
    assert {targets for targets, _, _ in lines} == {"run-local migrate-local demo-check-media"}
    exported = {name: path for _, name, path in lines}

    assert exported == {
        "CONFIG_FILE_PATH": "config.example.yml",
        "SECRETS_FILE_PATH": "secrets.example.yml",
    }
    assert all((_ROOT / path).is_file() for path in exported.values())
