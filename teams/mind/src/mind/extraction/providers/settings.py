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

The files themselves are read by `mind.config_file`, the one loader shared
with `mind.config` and `mind.messaging.config`.
"""

from dataclasses import dataclass
from typing import Any

from mind import config_file

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


def _present(section: dict[str, Any], key: str) -> Any | None:
    """The value at `key`, or None when it is missing *or empty*.

    A half-filled config file is the normal way a key goes missing: someone
    writes `api_key:` and leaves the value for later. YAML reads that as None,
    and wrapping it in `str()` turns it into the string "None" — which is
    truthy, so `configured` would report yes, the provider would start, and
    every call would come back 401 with nothing pointing at the real cause.

    Whitespace counts as empty for the same reason: `api_key: "   "` is a
    value nobody meant to set.
    """

    value = section.get(key)
    if value is None:
        return None
    if isinstance(value, str) and not value.strip():
        return None
    return value


def _llm_section() -> dict[str, Any]:
    """The whole `llm` subtree, merged across the two mounted files.

    A missing file is not an error: on a developer machine neither exists, and
    the result is simply empty. The provider is what refuses to start without
    a key, not this.
    """

    return config_file.section(config_file.load_merged_lenient(), "llm")


def _provider_section(provider: str) -> dict[str, Any]:
    """The `llm.<provider>` subtree: one provider's own settings."""

    return config_file.section(_llm_section(), provider)


def configured_provider_name() -> str | None:
    """Which provider `llm.provider` names, or None if nobody chose.

    A sibling of the per-provider sections rather than a key inside one:
    it is the choice between them, not a setting of either.
    """

    chosen = _present(_llm_section(), "provider")
    return str(chosen).strip().lower() if chosen is not None else None


def load_deepseek_settings() -> DeepSeekSettings:
    """Read the mounted files, falling back to the defaults above."""

    deepseek = _provider_section("deepseek")

    def value(key: str, fallback: Any) -> Any:
        read = _present(deepseek, key)
        return fallback if read is None else read

    return DeepSeekSettings(
        api_key=str(value("api_key", "")),
        base_url=str(value("base_url", DEFAULT_BASE_URL)),
        model=str(value("model", DEFAULT_MODEL)),
        timeout_seconds=float(value("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)),
        max_output_tokens=int(value("max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS)),
    )


def load_gemini_settings() -> GeminiSettings:
    """Read the mounted files, falling back to the defaults above.

    `thinking_budget` is read with a sentinel rather than a plain default, so
    that an explicit `thinking_budget: null` in the config means "omit the
    field" instead of being silently replaced by the default.
    """

    gemini = _provider_section("gemini")

    def value(key: str, fallback: Any) -> Any:
        read = _present(gemini, key)
        return fallback if read is None else read

    # thinking_budget is read straight off the section rather than through
    # `value`: here an explicit `thinking_budget: null` means "leave the field
    # out of the request", which is a real setting and not an empty one.
    budget = gemini.get("thinking_budget", GEMINI_DEFAULT_THINKING_BUDGET)

    return GeminiSettings(
        api_key=str(value("api_key", "")),
        base_url=str(value("base_url", GEMINI_DEFAULT_BASE_URL)),
        api_version=str(value("api_version", GEMINI_DEFAULT_API_VERSION)),
        model=str(value("model", GEMINI_DEFAULT_MODEL)),
        timeout_seconds=float(value("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)),
        max_output_tokens=int(value("max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS)),
        thinking_budget=None if budget is None else int(budget),
    )
