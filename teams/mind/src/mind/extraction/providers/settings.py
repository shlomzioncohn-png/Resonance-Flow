"""Where a provider's endpoint and credentials come from.

This mirrors `mind.messaging.config` on purpose: the cluster hands Mind its
settings as two files mounted by the Helm chart, not as environment variables,
and an LLM key is no different from a Kafka password.

    config.yaml   non-secret  -> $CONFIG_FILE_PATH   (components.mind.config)
    secrets.yaml  secret      -> $SECRETS_FILE_PATH  (components.mind.secret)

The keys read here, one subtree per provider:

    # config.yaml
    llm:
      deepseek:
        base_url: https://api.deepseek.com
        model: deepseek-chat
        timeout_seconds: 60
        max_output_tokens: 1024
      gemini:
        base_url: https://generativelanguage.googleapis.com
        model: gemini-3.6-flash
        timeout_seconds: 60
        max_output_tokens: 1024
        thinking_budget: 0
    # secrets.yaml
    llm:
      deepseek:
        api_key: sk-...
      gemini:
        api_key: ...

The file reading below is duplicated from `mind.messaging.config` rather than
imported out of it: extraction has no other reason to depend on messaging, and
the private helpers there are not an interface. The two copies must stay
identical, so if a third reader of these files appears, that is the moment to
factor all three into one loader instead of copying a third time.
"""

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

_DEFAULT_CONFIG = "/etc/resonance-flow/config/config.yaml"
_DEFAULT_SECRETS = "/etc/resonance-flow/secret/secrets.yaml"

DEFAULT_BASE_URL = "https://api.deepseek.com"
DEFAULT_MODEL = "deepseek-chat"
DEFAULT_TIMEOUT_SECONDS = 60.0

# An extraction answer is four small fields. Capping the output keeps a
# runaway generation from costing real money, and nothing we ask for comes
# anywhere near this.
DEFAULT_MAX_OUTPUT_TOKENS = 1024

GEMINI_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
GEMINI_DEFAULT_API_VERSION = "v1beta"

# Measured against the live API on 2026-10-06: of the models a new key may
# call, this one answered a trivial prompt in ~1.4s where the newest answered
# in ~101s or returned 503. Pinned rather than "latest" because a baseline is
# only comparable across weeks if the model behind it does not move.
GEMINI_DEFAULT_MODEL = "gemini-3.6-flash"

# Thinking off by default. Extraction asks for a handful of short fields and
# has nothing to reason about, and leaving it on cost ~100x the latency.
GEMINI_DEFAULT_THINKING_BUDGET = 0


@dataclass(frozen=True)
class DeepSeekSettings:
    """Everything needed to talk to DeepSeek, and nothing more.

    `model` defaults to `deepseek-chat`. Pointing it at `deepseek-reasoner`
    is not a configuration change alone: that model rejects `response_format`
    and ignores `temperature`, so the request body would have to change too.
    """

    api_key: str = ""
    base_url: str = DEFAULT_BASE_URL
    model: str = DEFAULT_MODEL
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    max_output_tokens: int = DEFAULT_MAX_OUTPUT_TOKENS

    # Zero, because two identical runs over the same corpus should produce
    # the same proposals: a confidence number is hard to read otherwise.
    temperature: float = 0.0

    @property
    def configured(self) -> bool:
        """Whether a key was actually mounted. False in a dev shell."""

        return bool(self.api_key)

    @property
    def completions_url(self) -> str:
        """The chat-completions endpoint, tolerant of a trailing slash."""

        return f"{self.base_url.rstrip('/')}/chat/completions"


@dataclass(frozen=True)
class GeminiSettings:
    """Everything needed to talk to Gemini, and nothing more.

    `thinking_budget` is `int | None` rather than `int`: not every model
    accepts the field, and `None` means "leave it out of the body" rather
    than "think as much as you like".
    """

    api_key: str = ""
    base_url: str = GEMINI_DEFAULT_BASE_URL
    api_version: str = GEMINI_DEFAULT_API_VERSION
    model: str = GEMINI_DEFAULT_MODEL
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    max_output_tokens: int = DEFAULT_MAX_OUTPUT_TOKENS
    temperature: float = 0.0
    thinking_budget: int | None = GEMINI_DEFAULT_THINKING_BUDGET

    @property
    def configured(self) -> bool:
        """Whether a key was actually mounted. False in a dev shell."""

        return bool(self.api_key)

    @property
    def generate_url(self) -> str:
        """The generateContent endpoint for the configured model."""

        return (
            f"{self.base_url.rstrip('/')}/{self.api_version}"
            f"/models/{self.model}:generateContent"
        )


def _read_yaml(path: str) -> dict[str, Any]:
    p = Path(path)
    if not p.is_file():
        return {}
    loaded = yaml.safe_load(p.read_text(encoding="utf-8"))
    return loaded if isinstance(loaded, dict) else {}


def _deep_merge(base: dict[str, Any], over: dict[str, Any]) -> dict[str, Any]:
    out = dict(base)
    for key, value in over.items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = _deep_merge(out[key], value)
        else:
            out[key] = value
    return out


def _provider_section(provider: str) -> dict[str, Any]:
    """The `llm.<provider>` subtree, merged across the two mounted files.

    A missing file is not an error: on a developer machine neither exists, and
    the result is simply an empty section. The provider is what refuses to
    start without a key, not this.
    """

    merged = _deep_merge(
        _read_yaml(os.environ.get("CONFIG_FILE_PATH", _DEFAULT_CONFIG)),
        _read_yaml(os.environ.get("SECRETS_FILE_PATH", _DEFAULT_SECRETS)),
    )
    llm = merged.get("llm", {}) or {}
    section = llm.get(provider, {}) or {}
    return section if isinstance(section, dict) else {}


def load_deepseek_settings() -> DeepSeekSettings:
    """Read the mounted files, falling back to the defaults above."""

    deepseek = _provider_section("deepseek")

    return DeepSeekSettings(
        api_key=str(deepseek.get("api_key", "")),
        base_url=str(deepseek.get("base_url", DEFAULT_BASE_URL)),
        model=str(deepseek.get("model", DEFAULT_MODEL)),
        timeout_seconds=float(deepseek.get("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)),
        max_output_tokens=int(deepseek.get("max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS)),
    )


def load_gemini_settings() -> GeminiSettings:
    """Read the mounted files, falling back to the defaults above.

    `thinking_budget` is read with a sentinel rather than a plain default, so
    that an explicit `thinking_budget: null` in the config means "omit the
    field" instead of being silently replaced by the default.
    """

    gemini = _provider_section("gemini")
    budget = gemini.get("thinking_budget", GEMINI_DEFAULT_THINKING_BUDGET)

    return GeminiSettings(
        api_key=str(gemini.get("api_key", "")),
        base_url=str(gemini.get("base_url", GEMINI_DEFAULT_BASE_URL)),
        api_version=str(gemini.get("api_version", GEMINI_DEFAULT_API_VERSION)),
        model=str(gemini.get("model") or GEMINI_DEFAULT_MODEL),
        timeout_seconds=float(gemini.get("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)),
        max_output_tokens=int(gemini.get("max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS)),
        thinking_budget=None if budget is None else int(budget),
    )
