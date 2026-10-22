"""Tests for loading provider settings out of the mounted config files."""

from pathlib import Path

import pytest

from mind.extraction.models import MAX_TASKS_PER_TEXT
from mind.extraction.providers.settings import (
    DEFAULT_MAX_OUTPUT_TOKENS,
    DEFAULT_MODEL,
    DEFAULT_TIMEOUT_SECONDS,
    GEMINI_DEFAULT_MODEL,
    TOKENS_PER_TASK,
    DeepSeekSettings,
    GeminiSettings,
    load_deepseek_settings,
    load_gemini_settings,
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


# --- gemini --------------------------------------------------------------


def test_each_provider_reads_only_its_own_subtree(_mounted: Path) -> None:
    """One file holds every provider; a loader must not pick up a neighbour."""

    (_mounted / "secrets.yaml").write_text(
        "llm:\n"
        "  deepseek:\n    api_key: sk-deepseek\n"
        "  gemini:\n    api_key: gemini-key\n",
        encoding="utf-8",
    )

    assert load_deepseek_settings().api_key == "sk-deepseek"
    assert load_gemini_settings().api_key == "gemini-key"


def test_the_gemini_model_defaults_to_the_pinned_one(_mounted: Path) -> None:
    """Pinned rather than latest: a baseline holds only if the model does."""

    assert load_gemini_settings().model == GEMINI_DEFAULT_MODEL


def test_an_empty_model_falls_back_rather_than_building_a_broken_url(_mounted: Path) -> None:
    """The local config file ships with an empty model until it is filled in."""

    (_mounted / "config.yaml").write_text(
        'llm:\n  gemini:\n    model: ""\n', encoding="utf-8"
    )

    assert load_gemini_settings().model == GEMINI_DEFAULT_MODEL


def test_thinking_stays_off_by_default_and_null_means_omit_it(_mounted: Path) -> None:
    """None means leave the field out, not think as much as you like."""

    assert load_gemini_settings().thinking_budget == 0

    (_mounted / "config.yaml").write_text(
        "llm:\n  gemini:\n    thinking_budget: null\n", encoding="utf-8"
    )

    assert load_gemini_settings().thinking_budget is None


def test_the_generate_url_carries_the_model_and_the_api_version() -> None:
    settings = GeminiSettings(model="gemini-3.6-flash", base_url="https://host/")

    assert settings.generate_url == "https://host/v1beta/models/gemini-3.6-flash:generateContent"


# --- a key that is present but empty --------------------------------------


def test_a_key_present_but_empty_counts_as_missing(_mounted: Path) -> None:
    """`api_key:` with nothing after it is how a half-filled file looks.

    YAML reads that as None, and `str(None)` is the string "None" — which is
    truthy, so `configured` would say yes and the provider would start and
    then 401 on every call. The check exists to prevent exactly that.
    """

    (_mounted / "secrets.yaml").write_text("llm:\n  deepseek:\n    api_key:\n", encoding="utf-8")

    settings = load_deepseek_settings()

    assert settings.api_key == ""
    assert settings.configured is False


def test_a_key_of_only_spaces_counts_as_missing(_mounted: Path) -> None:
    (_mounted / "secrets.yaml").write_text(
        'llm:\n  deepseek:\n    api_key: "   "\n', encoding="utf-8"
    )

    assert load_deepseek_settings().configured is False


def test_an_empty_value_falls_back_to_the_default_not_to_the_word_none(
    _mounted: Path,
) -> None:
    """The same bug in a field that is not the key: an empty `model:` built a
    URL pointing at a model literally called "None"."""

    (_mounted / "config.yaml").write_text(
        "llm:\n  deepseek:\n    model:\n    timeout_seconds:\n", encoding="utf-8"
    )

    settings = load_deepseek_settings()

    assert settings.model == DEFAULT_MODEL
    assert settings.timeout_seconds == DEFAULT_TIMEOUT_SECONDS


def test_the_same_holds_for_gemini(_mounted: Path) -> None:
    """Two loaders, one bug — so the test covers both rather than one."""

    (_mounted / "secrets.yaml").write_text("llm:\n  gemini:\n    api_key:\n", encoding="utf-8")
    (_mounted / "config.yaml").write_text("llm:\n  gemini:\n    model:\n", encoding="utf-8")

    settings = load_gemini_settings()

    assert settings.api_key == ""
    assert settings.configured is False
    assert settings.model == GEMINI_DEFAULT_MODEL


def test_the_default_output_cap_holds_a_full_list_of_tasks() -> None:
    """Gemini cuts an answer at the cap, and a cut answer is refused, not
    repaired: a meeting past the cap would come back with no task at all.
    Raising MAX_TASKS_PER_TEXT without this cap fails here first."""

    assert DEFAULT_MAX_OUTPUT_TOKENS >= MAX_TASKS_PER_TEXT * TOKENS_PER_TASK
