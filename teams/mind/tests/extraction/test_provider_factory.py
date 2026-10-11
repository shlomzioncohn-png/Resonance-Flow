"""Choosing a provider from configuration rather than from code.

The point of `LLMProvider` being a protocol is that swapping DeepSeek for
Gemini should not be a code change. Until this existed, it was: every caller
named a concrete class, so the claim in `base.py` was not true.
"""

from pathlib import Path

import pytest

from mind.extraction.providers.base import ManagedLLMProvider
from mind.extraction.providers.deepseek import DeepSeekProvider
from mind.extraction.providers.factory import KNOWN_PROVIDERS, build_provider
from mind.extraction.providers.gemini import GeminiProvider


@pytest.fixture(autouse=True)
def _mounted(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv("CONFIG_FILE_PATH", str(tmp_path / "config.yaml"))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(tmp_path / "secrets.yaml"))
    return tmp_path


def _configure(mounted: Path, provider: str | None, key: str = "a-test-key") -> None:
    chosen = f"  provider: {provider}\n" if provider is not None else ""
    (mounted / "config.yaml").write_text(f"llm:\n{chosen}", encoding="utf-8")
    (mounted / "secrets.yaml").write_text(
        f"llm:\n  deepseek:\n    api_key: {key}\n  gemini:\n    api_key: {key}\n",
        encoding="utf-8",
    )


def test_the_configured_provider_is_the_one_built(_mounted: Path) -> None:
    _configure(_mounted, "gemini")

    provider = build_provider()

    assert isinstance(provider, GeminiProvider)
    provider.close()


def test_the_other_name_builds_the_other_client(_mounted: Path) -> None:
    _configure(_mounted, "deepseek")

    provider = build_provider()

    assert isinstance(provider, DeepSeekProvider)
    provider.close()


def test_what_comes_back_is_only_promised_to_be_a_provider(_mounted: Path) -> None:
    """Callers depend on the protocol, which is the whole point."""

    _configure(_mounted, "gemini")

    provider: ManagedLLMProvider = build_provider()

    assert provider.name in KNOWN_PROVIDERS
    provider.close()


def test_no_choice_is_an_error_rather_than_a_default(_mounted: Path) -> None:
    """Deliberately no default.

    DeepSeek is the issue's primary provider but is unreachable on some
    networks; Gemini is reachable but choosing it here would be this module
    inventing a policy. Either silent default hands someone a provider they
    did not pick, and the failure arrives much later as a timeout.
    """

    _configure(_mounted, None)

    with pytest.raises(ValueError) as raised:
        build_provider()

    message = str(raised.value)
    assert "llm.provider" in message
    assert "deepseek" in message and "gemini" in message


def test_an_unknown_name_says_what_the_known_ones_are(_mounted: Path) -> None:
    _configure(_mounted, "chatgpt")

    with pytest.raises(ValueError) as raised:
        build_provider()

    assert "chatgpt" in str(raised.value)
    assert "gemini" in str(raised.value)


@pytest.mark.parametrize("written", ["Gemini", "  gemini  ", "GEMINI"])
def test_the_name_is_read_the_way_the_rest_of_the_config_is(
    _mounted: Path, written: str
) -> None:
    """Same forgiveness as everywhere else: casing is not a configuration."""

    _configure(_mounted, written)

    provider = build_provider()

    assert isinstance(provider, GeminiProvider)
    provider.close()


def test_an_explicit_name_overrides_the_file(_mounted: Path) -> None:
    """For a script that knows which provider it wants to exercise."""

    _configure(_mounted, "deepseek")

    provider = build_provider("gemini")

    assert isinstance(provider, GeminiProvider)
    provider.close()


def test_a_chosen_provider_with_no_key_still_refuses_to_start(_mounted: Path) -> None:
    """The factory picks; the provider is what insists on being usable."""

    (_mounted / "config.yaml").write_text("llm:\n  provider: gemini\n", encoding="utf-8")

    with pytest.raises(ValueError, match="api_key is not configured"):
        build_provider()
