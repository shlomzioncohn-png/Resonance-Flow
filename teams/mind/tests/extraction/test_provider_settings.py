"""Tests for loading provider settings out of the mounted config files."""

from pathlib import Path

import pytest

from mind.extraction.providers.settings import (
    DEFAULT_MODEL,
    DeepSeekSettings,
    load_deepseek_settings,
)


@pytest.fixture(autouse=True)
def _mounted(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Point the loader at a temp directory instead of the real mounts.

    Autouse so no test can accidentally read a real `/etc/resonance-flow`
    file, or a real key, off the machine running the suite.
    """

    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "config.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "secrets.yaml"))
    return tmp_path


def test_no_files_at_all_gives_an_unconfigured_object(_mounted: Path) -> None:
    """A dev machine has neither file, and that is not an error here."""

    settings = load_deepseek_settings()

    assert settings.configured is False
    assert settings.model == DEFAULT_MODEL


def test_the_non_secret_file_supplies_the_endpoint(_mounted: Path) -> None:
    (_mounted / "config.yaml").write_text(
        "llm:\n"
        "  deepseek:\n"
        "    base_url: https://proxy.internal/deepseek\n"
        "    model: deepseek-reasoner\n"
        "    timeout_seconds: 15\n"
        "    max_output_tokens: 512\n",
        encoding="utf-8",
    )

    settings = load_deepseek_settings()

    assert settings.base_url == "https://proxy.internal/deepseek"
    assert settings.model == "deepseek-reasoner"
    assert settings.timeout_seconds == 15.0
    assert settings.max_output_tokens == 512


def test_the_secret_file_supplies_the_key(_mounted: Path) -> None:
    (_mounted / "secrets.yaml").write_text(
        "llm:\n  deepseek:\n    api_key: sk-from-the-secret\n", encoding="utf-8"
    )

    settings = load_deepseek_settings()

    assert settings.api_key == "sk-from-the-secret"
    assert settings.configured is True


def test_the_two_files_merge_rather_than_replace(_mounted: Path) -> None:
    """The key and the endpoint live in different files but one subtree."""

    (_mounted / "config.yaml").write_text(
        "llm:\n  deepseek:\n    model: deepseek-chat\n", encoding="utf-8"
    )
    (_mounted / "secrets.yaml").write_text(
        "llm:\n  deepseek:\n    api_key: sk-merged\n", encoding="utf-8"
    )

    settings = load_deepseek_settings()

    assert settings.model == "deepseek-chat"
    assert settings.api_key == "sk-merged"


def test_an_empty_file_is_the_same_as_no_file(_mounted: Path) -> None:
    (_mounted / "config.yaml").write_text("", encoding="utf-8")

    assert load_deepseek_settings().model == DEFAULT_MODEL


def test_unrelated_keys_are_ignored(_mounted: Path) -> None:
    """The same files carry Kafka's settings; we read only our subtree."""

    (_mounted / "config.yaml").write_text(
        "kafka:\n  bootstrap_servers: broker:9092\nllm:\n  deepseek:\n    model: deepseek-chat\n",
        encoding="utf-8",
    )

    assert load_deepseek_settings().model == "deepseek-chat"


def test_the_endpoint_tolerates_a_trailing_slash() -> None:
    with_slash = DeepSeekSettings(base_url="https://api.deepseek.com/")
    without = DeepSeekSettings(base_url="https://api.deepseek.com")

    assert with_slash.completions_url == without.completions_url
