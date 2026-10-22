"""Every Mind process refuses to start without its mounted config (#481),
and the LLM settings -- whose contract with Relay isn't agreed yet -- keep
their old lenient reading.
"""

import logging
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mind.config import get_settings
from mind.config_file import ConfigFileError


@pytest.fixture(autouse=True)
def _no_config(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """No CONFIG_FILE_PATH, and no Settings cached from an earlier test."""

    monkeypatch.delenv("CONFIG_FILE_PATH", raising=False)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_api_startup_fails_without_config() -> None:
    from mind.api.main import app

    # Entering the client runs the lifespan, i.e. the app's startup.
    with pytest.raises(ConfigFileError, match=r"\$CONFIG_FILE_PATH is not set"), TestClient(app):
        pass


def test_api_startup_applies_the_configured_log_level(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from mind.api import main

    (tmp_path / "config.yaml").write_text(
        "vector_store: {url: 'db.invalid:5432', user: mind, db: mind}\n"
        "service: {log_level: WARNING}\n",
        encoding="utf-8",
    )
    (tmp_path / "secrets.yaml").write_text("vector_store: {password: x}\n", encoding="utf-8")
    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "config.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "secrets.yaml"))
    monkeypatch.setattr(main, "PgVectorStore", _FakeStore)
    root = logging.getLogger()
    monkeypatch.setattr(root, "level", root.level)  # restored after the test

    with TestClient(main.app):
        assert root.level == logging.WARNING


def test_index_consumer_fails_before_starting_any_thread(monkeypatch: pytest.MonkeyPatch) -> None:
    from mind.embeddings import index_consumer_runner

    monkeypatch.setattr(index_consumer_runner.threading, "Thread", _no_threads)

    with pytest.raises(ConfigFileError, match=r"\$CONFIG_FILE_PATH is not set"):
        index_consumer_runner.main()


def test_score_publisher_fails_before_starting_any_thread(monkeypatch: pytest.MonkeyPatch) -> None:
    from mind.scoring import score_publisher_runner

    monkeypatch.setattr(score_publisher_runner.threading, "Thread", _no_threads)

    with pytest.raises(ConfigFileError, match=r"\$CONFIG_FILE_PATH is not set"):
        score_publisher_runner.main()


def test_llm_settings_still_tolerate_a_missing_config(monkeypatch: pytest.MonkeyPatch) -> None:
    """Unchanged on purpose: no error, just an unconfigured provider."""

    from mind.extraction.providers.settings import (
        configured_provider_name,
        load_deepseek_settings,
        load_gemini_settings,
    )

    monkeypatch.setenv("SECRETS_FILE_PATH", "does-not-exist.yaml")

    assert configured_provider_name() is None
    assert load_gemini_settings().configured is False
    assert load_deepseek_settings().configured is False


def _no_threads(*args: object, **kwargs: object) -> None:
    raise AssertionError("a worker thread was started before the config was checked")


class _FakeStore:
    """Stands in for PgVectorStore: these tests are about startup, not a database."""

    def __init__(self, settings: object) -> None:
        pass

    async def open(self) -> None:
        pass

    async def close(self) -> None:
        pass
