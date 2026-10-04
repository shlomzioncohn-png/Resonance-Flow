"""Where a provider's endpoint and credentials come from.

This mirrors `mind.messaging.config` on purpose: the cluster hands Mind its
settings as two files mounted by the Helm chart, not as environment variables,
and an LLM key is no different from a Kafka password.

    config.yaml   non-secret  -> $CONFIG_FILE_PATH   (components.mind.config)
    secrets.yaml  secret      -> $SECRETS_FILE_PATH  (components.mind.secret)

The keys read here:

    # config.yaml
    llm:
      deepseek:
        base_url: https://api.deepseek.com
        model: deepseek-chat
        timeout_seconds: 60
        max_output_tokens: 1024
    # secrets.yaml
    llm:
      deepseek:
        api_key: sk-...

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


def load_deepseek_settings() -> DeepSeekSettings:
    """Read the mounted files, falling back to the defaults above.

    A missing file is not an error here: on a developer machine neither file
    exists, and the result is simply an unconfigured settings object. The
    provider is what refuses to start without a key, not this.
    """

    merged = _deep_merge(
        _read_yaml(os.environ.get("CONFIG_FILE_PATH", _DEFAULT_CONFIG)),
        _read_yaml(os.environ.get("SECRETS_FILE_PATH", _DEFAULT_SECRETS)),
    )
    llm = merged.get("llm", {}) or {}
    deepseek = llm.get("deepseek", {}) or {}

    return DeepSeekSettings(
        api_key=str(deepseek.get("api_key", "")),
        base_url=str(deepseek.get("base_url", DEFAULT_BASE_URL)),
        model=str(deepseek.get("model", DEFAULT_MODEL)),
        timeout_seconds=float(deepseek.get("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)),
        max_output_tokens=int(deepseek.get("max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS)),
    )
